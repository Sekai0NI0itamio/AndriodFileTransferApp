/*
 * router_tunnel - macOS TCP proxy that bypasses VPN routing
 *
 * Uses the macOS-specific IP_BOUND_IF socket option to force outbound
 * connections through a specific physical network interface (e.g. en0),
 * bypassing VPN tunnels that capture all traffic via routing table.
 *
 * Acts as a transparent Minecraft proxy: reads the MC handshake packet
 * to extract the real destination server, connects outbound via the
 * physical interface, then relays traffic bidirectionally.
 *
 * Usage:
 *   ./router_tunnel                           # auto-detect, port 25560
 *   ./router_tunnel --port 25560              # custom port
 *   ./router_tunnel --interface en0           # force interface
 *   ./router_tunnel --skip-test               # skip connectivity check
 *
 * The FDPClient mod connects to 127.0.0.1:<port> instead of the real
 * server. The handshake packet tells us where to actually connect.
 *
 * Health check: connect and send a single 0x00 byte.
 * The proxy responds with a JSON status and disconnects.
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <ctype.h>
#include <unistd.h>
#include <errno.h>
#include <signal.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <arpa/inet.h>
#include <netdb.h>
#include <net/if.h>
#include <ifaddrs.h>
#include <poll.h>
#include <fcntl.h>
#include <time.h>
#include <sys/time.h>

/* ---------- configuration ---------- */

#define DEFAULT_PORT        25560
#define DEFAULT_HTTP_PROXY_PORT 25561
#define BUFFER_SIZE         65536
#define CONNECT_TIMEOUT_MS  5000
#define RELAY_TIMEOUT_MS    300000   /* 5 min idle */
#define MAX_PACKET_SIZE     32767
#define HTTP_HEADER_MAX     16384
#define SOCKET_BUFFER_BYTES (1 << 20)
#define MAX_WIFI_NETWORKS   32

/* ---------- globals ---------- */

static volatile sig_atomic_t g_running = 1;
static char   g_interface[64]           = {0};
static int    g_ifindex                 = 0;
static char   g_local_ip[INET_ADDRSTRLEN] = {0};
static int    g_always_route_setup      = 0;

/* ---------- helpers ---------- */

static void sig_handler(int sig) {
    (void)sig;
    g_running = 0;
}

static void log_time(void) {
    time_t now = time(NULL);
    struct tm *t = localtime(&now);
    fprintf(stdout, "[%02d:%02d:%02d] ", t->tm_hour, t->tm_min, t->tm_sec);
}

#define LOG(fmt, ...) do { \
    log_time(); \
    fprintf(stdout, "[RouterTunnel] " fmt "\n", ##__VA_ARGS__); \
    fflush(stdout); \
} while (0)

#define ERR(fmt, ...) do { \
    log_time(); \
    fprintf(stderr, "[RouterTunnel] ERROR: " fmt "\n", ##__VA_ARGS__); \
    fflush(stderr); \
} while (0)

/* read exactly n bytes */
static ssize_t read_full(int fd, void *buf, size_t n) {
    size_t total = 0;
    while (total < n) {
        ssize_t r = read(fd, (char *)buf + total, n - total);
        if (r <= 0) return r == 0 ? (ssize_t)total : -1;
        total += r;
    }
    return (ssize_t)total;
}

/* write exactly n bytes */
static ssize_t write_full(int fd, const void *buf, size_t n) {
    size_t total = 0;
    while (total < n) {
        ssize_t w = write(fd, (const char *)buf + total, n - total);
        if (w < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        total += w;
    }
    return (ssize_t)total;
}

static void tune_socket(int fd) {
    int one = 1;
    int buf = SOCKET_BUFFER_BYTES;

    setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &one, sizeof(one));
    setsockopt(fd, SOL_SOCKET, SO_KEEPALIVE, &one, sizeof(one));
#ifdef SO_NOSIGPIPE
    setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, &one, sizeof(one));
#endif
    setsockopt(fd, SOL_SOCKET, SO_RCVBUF, &buf, sizeof(buf));
    setsockopt(fd, SOL_SOCKET, SO_SNDBUF, &buf, sizeof(buf));
}

/* read VarInt from buffer */
static int read_varint_buf(const unsigned char *buf, int len,
                           int *offset, int *value)
{
    *value = 0;
    int shift = 0;
    while (*offset < len) {
        unsigned char b = buf[(*offset)++];
        *value |= (b & 0x7F) << shift;
        shift += 7;
        if (!(b & 0x80)) return 0;
        if (shift >= 35) return -1;
    }
    return -1;
}

/* ---------- HTTP proxy helpers ---------- */

static int find_header_end(const char *buf, int len) {
    for (int i = 3; i < len; i++) {
        if (buf[i - 3] == '\r' && buf[i - 2] == '\n' &&
            buf[i - 1] == '\r' && buf[i] == '\n')
            return i + 1;
    }
    return -1;
}

static int read_http_header(int fd, char *buf, int max,
                            int *out_total, int *out_header_len)
{
    int total = 0;
    while (total < max) {
        ssize_t n = read(fd, buf + total, max - total);
        if (n <= 0) return -1;
        total += (int)n;
        int end = find_header_end(buf, total);
        if (end > 0) {
            *out_total = total;
            *out_header_len = end;
            return 0;
        }
    }
    return -1;
}

static void http_send_error(int fd, int code, const char *msg) {
    char resp[256];
    int n = snprintf(resp, sizeof(resp),
                     "HTTP/1.1 %d %s\r\n"
                     "Content-Length: 0\r\n"
                     "Connection: close\r\n"
                     "\r\n",
                     code, msg);
    write_full(fd, resp, (size_t)n);
}

static int parse_hostport(const char *in, char *host, size_t host_sz,
                          unsigned short *port, unsigned short default_port)
{
    const char *colon = strrchr(in, ':');
    if (colon && colon != in && strchr(colon + 1, ':') == NULL) {
        int p = atoi(colon + 1);
        if (p <= 0 || p > 65535) return -1;
        *port = (unsigned short)p;
        size_t hlen = (size_t)(colon - in);
        if (hlen == 0 || hlen >= host_sz) return -1;
        memcpy(host, in, hlen);
        host[hlen] = '\0';
        return 0;
    }
    if (strlen(in) >= host_sz) return -1;
    strcpy(host, in);
    *port = default_port;
    return 0;
}



/* set fd non-blocking */
static int set_nonblock(int fd) {
    int flags = fcntl(fd, F_GETFL, 0);
    return fcntl(fd, F_SETFL, flags | O_NONBLOCK);
}

static int should_retry_with_route(int err) {
    return err == ENETUNREACH ||
           err == EHOSTUNREACH ||
           err == EADDRNOTAVAIL ||
           err == ETIMEDOUT;
}

/* connect with timeout (ms) */
static int connect_with_timeout(int fd, const struct sockaddr *addr,
                                socklen_t addrlen, int timeout_ms)
{
    set_nonblock(fd);

    int ret = connect(fd, addr, addrlen);
    if (ret == 0) {
        int f = fcntl(fd, F_GETFL, 0);
        fcntl(fd, F_SETFL, f & ~O_NONBLOCK);
        return 0;
    }
    if (errno != EINPROGRESS) return -1;

    struct pollfd pfd = { .fd = fd, .events = POLLOUT };
    ret = poll(&pfd, 1, timeout_ms);
    if (ret <= 0) {
        errno = ret == 0 ? ETIMEDOUT : errno;
        return -1;
    }

    int err = 0;
    socklen_t errlen = sizeof(err);
    getsockopt(fd, SOL_SOCKET, SO_ERROR, &err, &errlen);
    if (err != 0) { errno = err; return -1; }

    int f = fcntl(fd, F_GETFL, 0);
    fcntl(fd, F_SETFL, f & ~O_NONBLOCK);
    return 0;
}

/* ---------- interface detection ---------- */

static int is_vpn_name(const char *name) {
    if (strncmp(name, "utun", 4) == 0) return 1;
    if (strncmp(name, "tun",  3) == 0) return 1;
    if (strncmp(name, "tap",  3) == 0) return 1;
    if (strncmp(name, "ppp",  3) == 0) return 1;
    if (strncmp(name, "ipsec",5) == 0) return 1;
    if (strncmp(name, "wg",   2) == 0) return 1;
    if (strstr(name, "vpn"))       return 1;
    if (strstr(name, "wireguard")) return 1;
    if (strstr(name, "tailscale")) return 1;
    if (strstr(name, "zerotier"))  return 1;
    return 0;
}

static int detect_interface(const char *manual_iface) {

    /* manual override */
    if (manual_iface && manual_iface[0]) {
        g_ifindex = if_nametoindex(manual_iface);
        if (g_ifindex == 0) { ERR("Interface '%s' not found", manual_iface); return -1; }
        strncpy(g_interface, manual_iface, sizeof(g_interface) - 1);

        struct ifaddrs *ifa_list, *ifa;
        if (getifaddrs(&ifa_list) == 0) {
            for (ifa = ifa_list; ifa; ifa = ifa->ifa_next) {
                if (!ifa->ifa_addr || ifa->ifa_addr->sa_family != AF_INET) continue;
                if (strcmp(ifa->ifa_name, manual_iface) != 0) continue;
                inet_ntop(AF_INET,
                          &((struct sockaddr_in *)ifa->ifa_addr)->sin_addr,
                          g_local_ip, sizeof(g_local_ip));
                break;
            }
            freeifaddrs(ifa_list);
        }
        return 0;
    }

    /* auto-detect */
    struct ifaddrs *ifa_list, *ifa;
    if (getifaddrs(&ifa_list) == -1) { ERR("getifaddrs: %s", strerror(errno)); return -1; }

    struct {
        char        name[IFNAMSIZ];
        char        ip[INET_ADDRSTRLEN];
        int         index;
        int         priority;
        unsigned int flags;
    } cand[32];
    int ncand = 0;

    for (ifa = ifa_list; ifa; ifa = ifa->ifa_next) {
        if (!ifa->ifa_addr || ifa->ifa_addr->sa_family != AF_INET) continue;
        if (!(ifa->ifa_flags & IFF_UP))       continue;
        if (  ifa->ifa_flags & IFF_LOOPBACK)  continue;
        if (  ifa->ifa_flags & IFF_POINTOPOINT) {
            LOG("Skipping point-to-point: %s", ifa->ifa_name);
            continue;
        }
        if (is_vpn_name(ifa->ifa_name)) {
            LOG("Skipping VPN interface: %s", ifa->ifa_name);
            continue;
        }
        if (ncand >= 32) break;

        int pri = 100;
        if (strcmp(ifa->ifa_name, "en0") == 0)       pri = 0;
        else if (strcmp(ifa->ifa_name, "en1") == 0)  pri = 1;
        else if (strncmp(ifa->ifa_name, "en", 2)==0) pri = 10;
        else if (strncmp(ifa->ifa_name, "bridge",6)==0) pri = 50;

        strncpy(cand[ncand].name, ifa->ifa_name, sizeof(cand[ncand].name) - 1);
        cand[ncand].name[sizeof(cand[ncand].name) - 1] = '\0';
        cand[ncand].index    = if_nametoindex(ifa->ifa_name);
        cand[ncand].priority = pri;
        cand[ncand].flags    = (unsigned int)ifa->ifa_flags;
        inet_ntop(AF_INET,
                  &((struct sockaddr_in *)ifa->ifa_addr)->sin_addr,
                  cand[ncand].ip, sizeof(cand[ncand].ip));
        LOG("Candidate: %s (%s) priority=%d flags=0x%x",
            cand[ncand].name, cand[ncand].ip, pri, cand[ncand].flags);
        ncand++;
    }
    freeifaddrs(ifa_list);

    if (ncand == 0) { ERR("No suitable non-VPN interface found"); return -1; }

    int best = 0;
    for (int i = 1; i < ncand; i++)
        if (cand[i].priority < cand[best].priority) best = i;

    strncpy(g_interface, cand[best].name, sizeof(g_interface) - 1);
    strncpy(g_local_ip,  cand[best].ip,   sizeof(g_local_ip)  - 1);
    g_ifindex = cand[best].index;
    return 0;
}

/* ---------- status refresh + Wi-Fi helpers ---------- */

static int detect_gateway(void);

static void refresh_local_ip(void) {
    struct ifaddrs *ifa_list, *ifa;
    if (getifaddrs(&ifa_list) != 0) return;
    for (ifa = ifa_list; ifa; ifa = ifa->ifa_next) {
        if (!ifa->ifa_addr || ifa->ifa_addr->sa_family != AF_INET) continue;
        if (strcmp(ifa->ifa_name, g_interface) != 0) continue;
        inet_ntop(AF_INET,
                  &((struct sockaddr_in *)ifa->ifa_addr)->sin_addr,
                  g_local_ip, sizeof(g_local_ip));
        break;
    }
    freeifaddrs(ifa_list);
}

static void json_escape(const char *in, char *out, size_t out_sz) {
    size_t o = 0;
    if (out_sz == 0) return;

    for (size_t i = 0; in[i] != '\0' && o + 2 < out_sz; i++) {
        unsigned char c = (unsigned char)in[i];
        if (c == '\\' || c == '"') {
            if (o + 2 >= out_sz) break;
            out[o++] = '\\';
            out[o++] = (char)c;
        } else if (c == '\n') {
            if (o + 2 >= out_sz) break;
            out[o++] = '\\';
            out[o++] = 'n';
        } else if (c == '\r') {
            if (o + 2 >= out_sz) break;
            out[o++] = '\\';
            out[o++] = 'r';
        } else if (c == '\t') {
            if (o + 2 >= out_sz) break;
            out[o++] = '\\';
            out[o++] = 't';
        } else if (c < 32) {
            out[o++] = ' ';
        } else {
            out[o++] = (char)c;
        }
    }
    out[o] = '\0';
}

static void send_json(int fd, const char *json) {
    size_t len = strlen(json);
    if (len <= 254) {
        unsigned char blen = (unsigned char)len;
        write_full(fd, &blen, 1);
        write_full(fd, json, len);
        return;
    }

    /* Extended frame: 0xFF + uint16 len (BE) + payload */
    if (len > 65535) len = 65535;
    unsigned char marker = 0xFF;
    unsigned char hi = (unsigned char)((len >> 8) & 0xFF);
    unsigned char lo = (unsigned char)(len & 0xFF);
    write_full(fd, &marker, 1);
    write_full(fd, &hi, 1);
    write_full(fd, &lo, 1);
    write_full(fd, json, len);
}


static void shell_quote_single(const char *in, char *out, size_t out_sz) {
    size_t o = 0;
    if (out_sz < 3) { if (out_sz) out[0] = '\0'; return; }

    out[o++] = '\'';
    for (size_t i = 0; in[i] != '\0' && o + 6 < out_sz; i++) {
        if (in[i] == '\'') {
            out[o++] = '\'';
            out[o++] = '\\';
            out[o++] = '\'';
            out[o++] = '\'';
        } else {
            out[o++] = in[i];
        }
    }
    out[o++] = '\'';
    out[o] = '\0';
}

static int get_console_user(char *out, size_t out_sz) {
    if (out_sz == 0) return -1;
    out[0] = '\0';

    FILE *fp = popen("stat -f%Su /dev/console 2>/dev/null", "r");
    if (!fp) return -1;

    char line[128] = {0};
    if (fgets(line, sizeof(line), fp)) {
        size_t len = strlen(line);
        while (len > 0 && (line[len - 1] == '\n' || line[len - 1] == '\r'))
            line[--len] = '\0';
        if (len > 0 && strcmp(line, "root") != 0) {
            strncpy(out, line, out_sz - 1);
            out[out_sz - 1] = '\0';
        }
    }

    pclose(fp);
    return out[0] ? 0 : -1;
}

static void build_networksetup_prefix(char *out, size_t out_sz) {
    out[0] = '\0';
    if (geteuid() != 0) return;

    char user[64] = {0};
    if (get_console_user(user, sizeof(user)) != 0) return;

    char quoted_user[128];
    shell_quote_single(user, quoted_user, sizeof(quoted_user));
    snprintf(out, out_sz, "sudo -u %s ", quoted_user);
}

static int output_has_error(const char *msg) {
    if (!msg || msg[0] == '\0') return 0;

    char lower[512];
    size_t n = strlen(msg);
    if (n >= sizeof(lower)) n = sizeof(lower) - 1;
    for (size_t i = 0; i < n; i++) lower[i] = (char)tolower((unsigned char)msg[i]);
    lower[n] = '\0';

    return strstr(lower, "error") || strstr(lower, "failed") || strstr(lower, "authorizationcreate");
}

static void run_cmd_collect(const char *cmd, char *out_msg, size_t out_msg_sz) {
    out_msg[0] = '\0';

    FILE *fp = popen(cmd, "r");
    if (!fp) {
        snprintf(out_msg, out_msg_sz, "popen failed: %s", strerror(errno));
        return;
    }

    char line[256];
    size_t used = 0;
    while (fgets(line, sizeof(line), fp) && used + 2 < out_msg_sz) {
        size_t len = strlen(line);
        while (len > 0 && (line[len - 1] == '\n' || line[len - 1] == '\r')) {
            line[--len] = '\0';
        }
        if (len == 0) continue;

        if (used > 0 && used + 2 < out_msg_sz) {
            out_msg[used++] = ';';
            out_msg[used++] = ' ';
            out_msg[used] = '\0';
        }

        size_t copy = len;
        if (copy > out_msg_sz - 1 - used) copy = out_msg_sz - 1 - used;
        memcpy(out_msg + used, line, copy);
        used += copy;
        out_msg[used] = '\0';
    }

    pclose(fp);
}

static int get_wifi_password(const char *ssid, char *out_pw, size_t out_pw_sz,
                             char *out_msg, size_t out_msg_sz)
{
    out_pw[0] = '\0';
    out_msg[0] = '\0';

    char prefix[128];
    build_networksetup_prefix(prefix, sizeof(prefix));

    char quoted_ssid[256];
    shell_quote_single(ssid, quoted_ssid, sizeof(quoted_ssid));

    char cmd[640];
    snprintf(cmd, sizeof(cmd),
             "%s/usr/bin/security find-generic-password -D 'AirPort network password' -a %s -w 2>/dev/null",
             prefix, quoted_ssid);

    LOG("Wi-Fi keychain lookup: %s/usr/bin/security find-generic-password -D 'AirPort network password' -a %s -w",
        prefix, quoted_ssid);

    FILE *fp = popen(cmd, "r");
    if (!fp) {
        snprintf(out_msg, out_msg_sz, "popen failed: %s", strerror(errno));
        return 0;
    }

    char line[256] = {0};
    if (fgets(line, sizeof(line), fp)) {
        size_t len = strlen(line);
        while (len > 0 && (line[len - 1] == '\n' || line[len - 1] == '\r'))
            line[--len] = '\0';
        if (len > 0) {
            if (len >= out_pw_sz) len = out_pw_sz - 1;
            memcpy(out_pw, line, len);
            out_pw[len] = '\0';
        }
    }

    pclose(fp);

    if (!out_pw[0]) {
        snprintf(out_msg, out_msg_sz, "No Wi-Fi password found in keychain");
        return 0;
    }
    return 1;
}

static int run_wifi_connect_cmd(const char *prefix,
                                const char *wifi_iface,
                                const char *quoted_ssid,
                                const char *quoted_pw,
                                int has_pw,
                                char *out_msg,
                                size_t out_msg_sz)
{
    char cmd[640];
    char logcmd[640];
    if (has_pw) {
        snprintf(cmd, sizeof(cmd),
                 "%s/usr/sbin/networksetup -setairportnetwork %s %s %s 2>&1",
                 prefix, wifi_iface, quoted_ssid, quoted_pw);
        snprintf(logcmd, sizeof(logcmd),
                 "%s/usr/sbin/networksetup -setairportnetwork %s %s <password>",
                 prefix, wifi_iface, quoted_ssid);
    } else {
        snprintf(cmd, sizeof(cmd),
                 "%s/usr/sbin/networksetup -setairportnetwork %s %s 2>&1",
                 prefix, wifi_iface, quoted_ssid);
        snprintf(logcmd, sizeof(logcmd),
                 "%s/usr/sbin/networksetup -setairportnetwork %s %s",
                 prefix, wifi_iface, quoted_ssid);
    }

    LOG("Wi-Fi connect: %s", logcmd);
    run_cmd_collect(cmd, out_msg, out_msg_sz);
    return !output_has_error(out_msg);
}

static int detect_wifi_interface(char *out, size_t out_sz) {
    out[0] = '\0';

    FILE *fp = popen("system_profiler SPAirPortDataType 2>/dev/null", "r");
    if (!fp) return -1;

    char line[512];
    int in_interfaces = 0;
    while (fgets(line, sizeof(line), fp)) {
        char *p = line;
        while (*p && isspace((unsigned char)*p)) p++;

        if (!in_interfaces) {
            if (strncmp(p, "Interfaces:", 11) == 0) {
                in_interfaces = 1;
            }
            continue;
        }

        char *colon = strchr(p, ':');
        if (!colon || colon == p) continue;
        if (!(colon[1] == '\0' || colon[1] == '\n' || isspace((unsigned char)colon[1]))) continue;

        size_t n = (size_t)(colon - p);
        if (n == 0 || n >= out_sz) continue;
        memcpy(out, p, n);
        out[n] = '\0';
        break;
    }

    pclose(fp);
    return out[0] ? 0 : -1;
}

static int wifi_connect(const char *ssid, char *out_msg, size_t out_msg_sz) {
    out_msg[0] = '\0';

    char wifi_iface[64] = {0};
    if (detect_wifi_interface(wifi_iface, sizeof(wifi_iface)) != 0) {
        /* fallback: try the tunnel interface if it looks like an ethernet name */
        if (strncmp(g_interface, "en", 2) == 0) {
            strncpy(wifi_iface, g_interface, sizeof(wifi_iface) - 1);
        } else {
            snprintf(out_msg, out_msg_sz, "Could not detect Wi-Fi interface");
            return 0;
        }
    }

    char quoted_ssid[256];
    shell_quote_single(ssid, quoted_ssid, sizeof(quoted_ssid));

    char prefix[128];
    build_networksetup_prefix(prefix, sizeof(prefix));

    int ok = run_wifi_connect_cmd("", wifi_iface, quoted_ssid, NULL, 0,
                                  out_msg, out_msg_sz);

    if (!ok) {
        char pw[256];
        char pw_msg[256];
        if (get_wifi_password(ssid, pw, sizeof(pw), pw_msg, sizeof(pw_msg))) {
            char quoted_pw[256];
            shell_quote_single(pw, quoted_pw, sizeof(quoted_pw));
            LOG("Wi-Fi password found in keychain (len=%zu), retrying", strlen(pw));
            ok = run_wifi_connect_cmd("", wifi_iface, quoted_ssid, quoted_pw, 1,
                                      out_msg, out_msg_sz);
            if (!ok && prefix[0]) {
                LOG("Retrying Wi-Fi connect as console user");
                ok = run_wifi_connect_cmd(prefix, wifi_iface, quoted_ssid, quoted_pw, 1,
                                          out_msg, out_msg_sz);
            }
        } else if (pw_msg[0]) {
            LOG("Wi-Fi password lookup: %s", pw_msg);
        }
    }

    /* allow DHCP/route to settle a bit */
    sleep(1);
    refresh_local_ip();
    detect_gateway();

    if (out_msg[0] == '\0') return ok ? 1 : 0;
    return ok ? 1 : 0;
}

static int wifi_list_preferred(char ssids[][128], int max_ssids, int *out_count,
                               char *out_msg, size_t out_msg_sz)
{
    *out_count = 0;
    out_msg[0] = '\0';

    char wifi_iface[64] = {0};
    if (detect_wifi_interface(wifi_iface, sizeof(wifi_iface)) != 0) {
        if (strncmp(g_interface, "en", 2) == 0) {
            strncpy(wifi_iface, g_interface, sizeof(wifi_iface) - 1);
        } else {
            snprintf(out_msg, out_msg_sz, "Could not detect Wi-Fi interface");
            return 0;
        }
    }

    char prefix[128];
    build_networksetup_prefix(prefix, sizeof(prefix));

    char cmd[640];
    snprintf(cmd, sizeof(cmd),
             "%s/usr/sbin/networksetup -listpreferredwirelessnetworks %s 2>&1",
             prefix, wifi_iface);

    LOG("Wi-Fi list: %s", cmd);
    FILE *fp = popen(cmd, "r");
    if (!fp) {
        snprintf(out_msg, out_msg_sz, "popen failed: %s", strerror(errno));
        return 0;
    }

    char line[512];
    int saw_error = 0;
    while (fgets(line, sizeof(line), fp)) {
        size_t len = strlen(line);
        while (len > 0 && (line[len - 1] == '\n' || line[len - 1] == '\r')) {
            line[--len] = '\0';
        }

        char *p = line;
        while (*p && isspace((unsigned char)*p)) p++;

        /* skip header */
        if (strncasecmp(p, "Preferred networks on", 21) == 0) continue;

        if (*p == '\0') continue;

        char lower[256];
        size_t n = strlen(p);
        if (n >= sizeof(lower)) n = sizeof(lower) - 1;
        for (size_t i = 0; i < n; i++) lower[i] = (char)tolower((unsigned char)p[i]);
        lower[n] = '\0';
        if (strstr(lower, "error") || strstr(lower, "authorizationcreate")) {
            saw_error = 1;
            if (out_msg[0] == '\0') {
                strncpy(out_msg, p, out_msg_sz - 1);
                out_msg[out_msg_sz - 1] = '\0';
            }
            continue;
        }

        if (*out_count < max_ssids) {
            strncpy(ssids[*out_count], p, 127);
            ssids[*out_count][127] = '\0';
            (*out_count)++;
        }
    }

    pclose(fp);

    if (saw_error && *out_count == 0) {
        if (out_msg[0] == '\0') snprintf(out_msg, out_msg_sz, "networksetup failed");
        return 0;
    }

    if (*out_count == 0 && out_msg[0] == '\0') {
        snprintf(out_msg, out_msg_sz, "No saved Wi-Fi networks");
    }

    return 1;
}

/* ---------- auto-route setup ---------- */

static char g_gateway[64] = {0};

/**
 * Parse the scoped default gateway for the chosen interface.
 * Uses: route -n get default -ifscope <interface>
 * Returns 0 on success, -1 on failure.
 */
static int parse_route_get_output(FILE *fp, char *out_gw, size_t out_gw_sz,
                                  char *out_iface, size_t out_iface_sz,
                                  char *out_dump, size_t out_dump_sz)
{
    char line[512];
    size_t used = 0;
    out_gw[0] = '\0';
    out_iface[0] = '\0';
    if (out_dump_sz) out_dump[0] = '\0';

    while (fgets(line, sizeof(line), fp)) {
        char *p;
        if ((p = strstr(line, "gateway:")) != NULL) {
            p += strlen("gateway:");
            while (*p && isspace((unsigned char)*p)) p++;
            if (*p) {
                char gw[64];
                int j = 0;
                while (*p && !isspace((unsigned char)*p) && j < (int)sizeof(gw) - 1)
                    gw[j++] = *p++;
                gw[j] = '\0';
                struct in_addr tmp;
                if (inet_pton(AF_INET, gw, &tmp) == 1) {
                    strncpy(out_gw, gw, out_gw_sz - 1);
                    out_gw[out_gw_sz - 1] = '\0';
                }
            }
        } else if ((p = strstr(line, "interface:")) != NULL) {
            p += strlen("interface:");
            while (*p && isspace((unsigned char)*p)) p++;
            if (*p) {
                char ifn[64];
                int j = 0;
                while (*p && !isspace((unsigned char)*p) && j < (int)sizeof(ifn) - 1)
                    ifn[j++] = *p++;
                ifn[j] = '\0';
                strncpy(out_iface, ifn, out_iface_sz - 1);
                out_iface[out_iface_sz - 1] = '\0';
            }
        }

        if (out_dump_sz > 0 && used + strlen(line) + 1 < out_dump_sz) {
            size_t len = strlen(line);
            while (len > 0 && (line[len - 1] == '\n' || line[len - 1] == '\r'))
                line[--len] = '\0';
            if (len > 0) {
                if (used > 0 && used + 2 < out_dump_sz) {
                    out_dump[used++] = ';';
                    out_dump[used++] = ' ';
                }
                size_t copy = len;
                if (copy > out_dump_sz - 1 - used) copy = out_dump_sz - 1 - used;
                memcpy(out_dump + used, line, copy);
                used += copy;
                out_dump[used] = '\0';
            }
        }
    }

    return out_gw[0] ? 0 : -1;
}

static int detect_gateway(void) {
    char cmd[256];
    char gw[64] = {0};
    char iface[64] = {0};
    char dump[1024] = {0};

    g_gateway[0] = '\0';

    snprintf(cmd, sizeof(cmd),
             "route -n get default -ifscope %s 2>/dev/null", g_interface);
    LOG("Detecting gateway (ifscope): %s", cmd);
    FILE *fp = popen(cmd, "r");
    if (fp) {
        (void)parse_route_get_output(fp, gw, sizeof(gw), iface, sizeof(iface), dump, sizeof(dump));
        pclose(fp);
        if (gw[0]) {
            strncpy(g_gateway, gw, sizeof(g_gateway) - 1);
            LOG("Detected gateway: %s (via %s)", g_gateway, g_interface);
            return 0;
        }
        if (dump[0]) LOG("Gateway detect (ifscope) output: %s", dump);
    } else {
        ERR("popen(route -n get) failed: %s", strerror(errno));
    }

    snprintf(cmd, sizeof(cmd),
             "ipconfig getoption %s router 2>/dev/null", g_interface);
    LOG("Detecting gateway (ipconfig): %s", cmd);
    fp = popen(cmd, "r");
    if (fp) {
        char line[256] = {0};
        if (fgets(line, sizeof(line), fp)) {
            char *p = line;
            while (*p && isspace((unsigned char)*p)) p++;
            char ip[64] = {0};
            int j = 0;
            while (*p && !isspace((unsigned char)*p) && j < (int)sizeof(ip) - 1)
                ip[j++] = *p++;
            ip[j] = '\0';
            struct in_addr tmp;
            if (inet_pton(AF_INET, ip, &tmp) == 1) {
                strncpy(g_gateway, ip, sizeof(g_gateway) - 1);
                pclose(fp);
                LOG("Detected gateway: %s (via %s)", g_gateway, g_interface);
                return 0;
            }
        }
        pclose(fp);
    }

    snprintf(cmd, sizeof(cmd), "route -n get default 2>/dev/null");
    LOG("Detecting gateway (default route): %s", cmd);
    fp = popen(cmd, "r");
    if (fp) {
        gw[0] = '\0';
        iface[0] = '\0';
        dump[0] = '\0';
        (void)parse_route_get_output(fp, gw, sizeof(gw), iface, sizeof(iface), dump, sizeof(dump));
        pclose(fp);
        if (gw[0] && iface[0] && strcmp(iface, g_interface) == 0) {
            strncpy(g_gateway, gw, sizeof(g_gateway) - 1);
            LOG("Detected gateway: %s (via %s)", g_gateway, g_interface);
            return 0;
        }
        if (dump[0]) LOG("Gateway detect (default route) output: %s", dump);
    }

    snprintf(cmd, sizeof(cmd), "netstat -rn -f inet 2>/dev/null");
    LOG("Detecting gateway (netstat): %s", cmd);
    fp = popen(cmd, "r");
    if (fp) {
        char line[512];
        while (fgets(line, sizeof(line), fp)) {
            char *p = line;
            while (*p && isspace((unsigned char)*p)) p++;
            if (strncmp(p, "default", 7) != 0 && strncmp(p, "0.0.0.0", 7) != 0)
                continue;

            char *tokens[16] = {0};
            int ntok = 0;
            char *save = NULL;
            for (char *t = strtok_r(p, " \t\r\n", &save);
                 t && ntok < 16;
                 t = strtok_r(NULL, " \t\r\n", &save)) {
                tokens[ntok++] = t;
            }
            if (ntok < 3) continue;

            int has_iface = 0;
            for (int i = 0; i < ntok; i++) {
                if (strcmp(tokens[i], g_interface) == 0) {
                    has_iface = 1;
                    break;
                }
            }
            if (!has_iface) continue;

            struct in_addr tmp;
            if (inet_pton(AF_INET, tokens[1], &tmp) == 1) {
                strncpy(g_gateway, tokens[1], sizeof(g_gateway) - 1);
                pclose(fp);
                LOG("Detected gateway: %s (via %s)", g_gateway, g_interface);
                return 0;
            }
        }
        pclose(fp);
    }

    ERR("Could not detect default gateway for %s", g_interface);
    return -1;
}

/**
 * Add a scoped default route through the detected interface.
 * Runs:  sudo route add default <gateway> -ifscope <interface>
 * Returns 0 on success, -1 on failure.
 */
static int setup_scoped_route(void) {
    if (g_gateway[0] == '\0') {
        if (detect_gateway() != 0) return -1;
    }

    /*
     * Do not delete/re-add on every refresh. Route churn can disturb active
     * flows. Attempt add only and treat "File exists" as already configured.
     */
    char cmd[256];
    snprintf(cmd, sizeof(cmd),
             "route add default %s -ifscope %s 2>&1",
             g_gateway, g_interface);

    LOG("Setting scoped route: %s", cmd);
    FILE *fp = popen(cmd, "r");
    if (!fp) { ERR("popen(route) failed: %s", strerror(errno)); return -1; }

    char out[512];
    int saw_hard_error = 0;
    int saw_exists = 0;
    while (fgets(out, sizeof(out), fp)) {
        /* trim trailing newline */
        size_t len = strlen(out);
        if (len > 0 && out[len - 1] == '\n') out[len - 1] = '\0';
        LOG("route: %s", out);
        if (strstr(out, "File exists")) {
            saw_exists = 1;
            continue;
        }
        if (strcasestr(out, "not permitted") ||
            strcasestr(out, "permission denied") ||
            strcasestr(out, "bad address") ||
            strcasestr(out, "no such process") ||
            strcasestr(out, "not in table")) {
            saw_hard_error = 1;
        }
    }
    /*
     * pclose() may return -1 / ECHILD because main() sets
     * signal(SIGCHLD, SIG_IGN), which auto-reaps children.
     * So we judge success by the command's output instead.
     */
    pclose(fp);

    if (saw_hard_error) {
        ERR("Route add appears to have failed (see output above)");
        return -1;
    }
    if (saw_exists)
        LOG("Scoped route already present");
    else
        LOG("Scoped route added successfully");
    return 0;
}

/**
 * Full auto-route: detect gateway + set scoped route.
 * Called on startup and on refresh packets.
 */
static int auto_setup_route(void) {
    LOG("--- Scoped route setup ---");
    if (detect_gateway() != 0) {
        LOG("--- Scoped route setup failed (gateway detect) ---");
        return -1;
    }
    if (setup_scoped_route() != 0) {
        LOG("--- Scoped route setup failed (route add) ---");
        return -1;
    }
    LOG("--- Scoped route setup done ---");
    return 0;
}

/* ---------- connectivity test ---------- */

static int test_connectivity(void) {
    LOG("Testing connectivity through %s (IP_BOUND_IF)...", g_interface);

    int fd = socket(AF_INET, SOCK_STREAM, 0);
    if (fd < 0) { ERR("socket: %s", strerror(errno)); return -1; }
    tune_socket(fd);

    if (setsockopt(fd, IPPROTO_IP, IP_BOUND_IF,
                   &g_ifindex, sizeof(g_ifindex)) < 0) {
        ERR("IP_BOUND_IF: %s", strerror(errno));
        close(fd);
        return -1;
    }

    struct sockaddr_in dst = {0};
    dst.sin_family = AF_INET;
    dst.sin_port   = htons(443);
    inet_pton(AF_INET, "1.1.1.1", &dst.sin_addr);

    int ret = connect_with_timeout(fd, (struct sockaddr *)&dst, sizeof(dst), 3000);
    close(fd);

    if (ret == 0) {
        LOG("Connectivity test PASSED (1.1.1.1:443 reachable via %s)", g_interface);
        return 0;
    }
    ERR("Connectivity test FAILED: %s", strerror(errno));
    ERR("You may need to add a scoped route:");
    ERR("  sudo route add default <gateway_ip> -ifscope %s", g_interface);
    return -1;
}

/* ---------- Minecraft handshake parser ---------- */

/*
 * Packet layout (after length VarInt):
 *   [VarInt packet_id=0x00]
 *   [VarInt protocol_version]
 *   [VarInt string_length] [UTF-8 host]
 *   [uint16 BE port]
 *   [VarInt next_state]
 */
static int parse_handshake(const unsigned char *data, int data_len,
                           char *host, int host_max, unsigned short *port)
{
    int off = 0;

    int packet_id;
    if (read_varint_buf(data, data_len, &off, &packet_id) != 0) return -1;
    if (packet_id != 0x00) return -1;

    int proto;
    if (read_varint_buf(data, data_len, &off, &proto) != 0) return -1;

    int slen;
    if (read_varint_buf(data, data_len, &off, &slen) != 0) return -1;
    if (slen <= 0 || slen > host_max - 1 || off + slen + 2 > data_len) return -1;

    memcpy(host, data + off, slen);
    host[slen] = '\0';
    off += slen;

    if (off + 2 > data_len) return -1;
    *port = (unsigned short)((data[off] << 8) | data[off + 1]);
    return 0;
}

/* ---------- outbound connect + relay ---------- */

static int connect_outbound(const char *host, unsigned short port,
                            char *rip, size_t rip_len)
{
    struct addrinfo hints = {0}, *result, *ai;
    hints.ai_family   = AF_INET;
    hints.ai_socktype = SOCK_STREAM;

    char port_str[16];
    snprintf(port_str, sizeof(port_str), "%u", port);

    int err = getaddrinfo(host, port_str, &hints, &result);
    if (err != 0) {
        ERR("DNS failed for %s: %s", host, gai_strerror(err));
        return -1;
    }

    int last_errno = 0;
    int retried_with_route = 0;

retry_connect:
    for (ai = result; ai; ai = ai->ai_next) {
        int remote_fd = socket(ai->ai_family, ai->ai_socktype, ai->ai_protocol);
        if (remote_fd < 0) {
            last_errno = errno;
            continue;
        }

        tune_socket(remote_fd);

        if (setsockopt(remote_fd, IPPROTO_IP, IP_BOUND_IF,
                       &g_ifindex, sizeof(g_ifindex)) < 0) {
            last_errno = errno;
            ERR("IP_BOUND_IF: %s", strerror(errno));
            close(remote_fd);
            continue;
        }

        inet_ntop(AF_INET,
                  &((struct sockaddr_in *)ai->ai_addr)->sin_addr,
                  rip, (socklen_t)rip_len);
        LOG("Connecting to %s (%s:%u) via %s ...", host, rip, port, g_interface);

        if (connect_with_timeout(remote_fd, ai->ai_addr,
                                 ai->ai_addrlen, CONNECT_TIMEOUT_MS) == 0) {
            freeaddrinfo(result);
            return remote_fd;
        }

        last_errno = errno;
        close(remote_fd);
    }

    if (!retried_with_route &&
        !g_always_route_setup &&
        should_retry_with_route(last_errno)) {
        LOG("Initial connect failed (%s); trying scoped route fallback once",
            strerror(last_errno));
        if (auto_setup_route() == 0) {
            retried_with_route = 1;
            goto retry_connect;
        }
    }

    freeaddrinfo(result);
    if (last_errno) errno = last_errno;
    ERR("Connect to %s:%u failed: %s", host, port, strerror(errno));
    return -1;
}


static void relay_bidirectional(int client_fd, int remote_fd,
                                const char *tag,
                                const char *host, unsigned short port)
{
    struct pollfd fds[2];
    fds[0].fd = client_fd;   fds[0].events = POLLIN;
    fds[1].fd = remote_fd;   fds[1].events = POLLIN;

    unsigned char buf[BUFFER_SIZE];
    unsigned long c2s = 0, s2c = 0;

    while (g_running) {
        int ready = poll(fds, 2, RELAY_TIMEOUT_MS);
        if (ready < 0) { if (errno == EINTR) continue; break; }
        if (ready == 0) { LOG("%s idle timeout for %s:%u", tag, host, port); break; }

        if (fds[0].revents & POLLIN) {
            ssize_t n = read(client_fd, buf, sizeof(buf));
            if (n <= 0) break;
            if (write_full(remote_fd, buf, n) != n) break;
            c2s += (unsigned long)n;
        }
        if (fds[0].revents & (POLLERR | POLLHUP | POLLNVAL)) break;

        if (fds[1].revents & POLLIN) {
            ssize_t n = read(remote_fd, buf, sizeof(buf));
            if (n <= 0) break;
            if (write_full(client_fd, buf, n) != n) break;
            s2c += (unsigned long)n;
        }
        if (fds[1].revents & (POLLERR | POLLHUP | POLLNVAL)) break;
    }

    LOG("%s relay closed %s:%u  (C->S %lu  S->C %lu bytes)",
        tag, host, port, c2s, s2c);
    close(remote_fd);
    close(client_fd);
}

/* ---------- client handler ---------- */

static void handle_control_client(int client_fd) {
    tune_socket(client_fd);

    unsigned char cmd;
    if (read_full(client_fd, &cmd, 1) != 1) {
        close(client_fd);
        return;
    }

    refresh_local_ip();

    /* --- health check: 0x00 --- */
    if (cmd == 0x00) {
        char resp[256];
        int rlen = snprintf(resp, sizeof(resp),
            "{\"status\":\"ok\",\"interface\":\"%s\",\"ip\":\"%s\"}",
            g_interface, g_local_ip);
        resp[sizeof(resp) - 1] = '\0';
        (void)rlen;
        send_json(client_fd, resp);
        close(client_fd);
        return;
    }

    /* --- refresh packet: 0x01 --- */
    if (cmd == 0x01) {
        LOG("Refresh packet received — rechecking gateway/route state");
        if (g_always_route_setup) {
            auto_setup_route();
        } else {
            detect_gateway();
        }

        refresh_local_ip();

        char resp[512];
        int rlen = snprintf(resp, sizeof(resp),
            "{\"status\":\"refreshed\",\"interface\":\"%s\",\"ip\":\"%s\",\"gateway\":\"%s\"}",
            g_interface, g_local_ip, g_gateway);
        resp[sizeof(resp) - 1] = '\0';
        (void)rlen;
        send_json(client_fd, resp);
        close(client_fd);
        return;
    }

    send_json(client_fd, "{\"status\":\"error\",\"message\":\"Unknown command\"}");
    close(client_fd);
}

static void handle_client(int client_fd) {
    char host[256] = {0};
    unsigned short port = 0;

    tune_socket(client_fd);

    /* --- read first byte --- */
    unsigned char first;
    if (read_full(client_fd, &first, 1) != 1) {
        close(client_fd);
        return;
    }

    /* --- health check: first byte == 0x00 --- */
    if (first == 0x00) {
        char resp[256];
        int rlen = snprintf(resp, sizeof(resp),
            "{\"status\":\"ok\",\"interface\":\"%s\",\"ip\":\"%s\"}",
            g_interface, g_local_ip);
        unsigned char blen = (unsigned char)rlen;
        write(client_fd, &blen, 1);
        write(client_fd, resp, rlen);
        close(client_fd);
        return;
    }

    /* --- refresh packet: first byte == 0x01 --- */
    if (first == 0x01) {
        LOG("Refresh packet received — rechecking gateway/route state");
        if (g_always_route_setup) {
            auto_setup_route();
        } else {
            detect_gateway();
        }

        char resp[512];
        int rlen = snprintf(resp, sizeof(resp),
            "{\"status\":\"refreshed\",\"interface\":\"%s\",\"ip\":\"%s\",\"gateway\":\"%s\"}",
            g_interface, g_local_ip, g_gateway);
        unsigned char blen = (unsigned char)rlen;
        write(client_fd, &blen, 1);
        write(client_fd, resp, rlen);
        close(client_fd);
        return;
    }

    /* --- Minecraft connection: reconstruct length VarInt --- */
    unsigned char len_raw[5];
    int  len_raw_n = 0;
    int  packet_length = first & 0x7F;
    int  shift = 7;

    len_raw[len_raw_n++] = first;

    if (first & 0x80) {
        unsigned char b;
        do {
            if (read_full(client_fd, &b, 1) != 1) { close(client_fd); return; }
            len_raw[len_raw_n++] = b;
            packet_length |= (b & 0x7F) << shift;
            shift += 7;
            if (shift >= 35) { ERR("VarInt too large"); close(client_fd); return; }
        } while (b & 0x80);
    }

    if (packet_length <= 0 || packet_length > MAX_PACKET_SIZE) {
        ERR("Invalid packet length: %d", packet_length);
        close(client_fd);
        return;
    }

    unsigned char *pkt = (unsigned char *)malloc(packet_length);
    if (!pkt) { ERR("malloc"); close(client_fd); return; }

    if (read_full(client_fd, pkt, packet_length) != packet_length) {
        ERR("Short read on handshake (%d bytes expected)", packet_length);
        free(pkt); close(client_fd);
        return;
    }

    /* --- parse the handshake --- */
    if (parse_handshake(pkt, packet_length, host, sizeof(host), &port) != 0) {
        ERR("Failed to parse Minecraft handshake");
        free(pkt); close(client_fd);
        return;
    }

    LOG("Handshake: target=%s:%d", host, port);

    char rip[INET_ADDRSTRLEN] = {0};
    int remote_fd = connect_outbound(host, port, rip, sizeof(rip));
    if (remote_fd < 0) {
        free(pkt);
        close(client_fd);
        return;
    }
    LOG("Connected to %s:%d", host, port);

    /* --- forward original handshake verbatim --- */
    if (write_full(remote_fd, len_raw, len_raw_n) != len_raw_n ||
        write_full(remote_fd, pkt, packet_length) != packet_length) {
        ERR("Failed to forward handshake");
        free(pkt); close(remote_fd); close(client_fd);
        return;
    }
    free(pkt);

    LOG("Handshake forwarded, starting relay");
    relay_bidirectional(client_fd, remote_fd, "MC", host, port);
}

/* ---------- HTTP proxy handler ---------- */

static void handle_http_client(int client_fd) {
    tune_socket(client_fd);

    char buf[HTTP_HEADER_MAX + 1];
    int total = 0, header_len = 0;
    if (read_http_header(client_fd, buf, HTTP_HEADER_MAX, &total, &header_len) != 0) {
        http_send_error(client_fd, 400, "Bad Request");
        close(client_fd);
        return;
    }
    buf[total] = '\0';

    int line_end = -1;
    for (int i = 0; i < header_len - 1; i++) {
        if (buf[i] == '\r' && buf[i + 1] == '\n') { line_end = i; break; }
    }
    if (line_end <= 0) {
        http_send_error(client_fd, 400, "Bad Request");
        close(client_fd);
        return;
    }

    char line[2048];
    if (line_end >= (int)sizeof(line)) {
        http_send_error(client_fd, 414, "Request-URI Too Long");
        close(client_fd);
        return;
    }
    memcpy(line, buf, (size_t)line_end);
    line[line_end] = '\0';

    char method[16], target[2048], version[16];
    if (sscanf(line, "%15s %2047s %15s", method, target, version) != 3) {
        http_send_error(client_fd, 400, "Bad Request");
        close(client_fd);
        return;
    }

    int is_connect = (strcasecmp(method, "CONNECT") == 0);
    char host[256] = {0};
    unsigned short port = 0;
    char path[2048] = "/";
    int saw_host_hdr = 0;

    if (is_connect) {
        if (parse_hostport(target, host, sizeof(host), &port, 443) != 0) {
            http_send_error(client_fd, 400, "Bad CONNECT Target");
            close(client_fd);
            return;
        }
    } else {
        if (strncmp(target, "http://", 7) == 0) {
            const char *p = target + 7;
            const char *slash = strchr(p, '/');
            const char *host_end = slash ? slash : p + strlen(p);
            char hostport[256];
            size_t hlen = (size_t)(host_end - p);
            if (hlen == 0 || hlen >= sizeof(hostport)) {
                http_send_error(client_fd, 400, "Bad Request");
                close(client_fd);
                return;
            }
            memcpy(hostport, p, hlen);
            hostport[hlen] = '\0';
            if (parse_hostport(hostport, host, sizeof(host), &port, 80) != 0) {
                http_send_error(client_fd, 400, "Bad Request");
                close(client_fd);
                return;
            }
            if (slash) {
                strncpy(path, slash, sizeof(path) - 1);
                path[sizeof(path) - 1] = '\0';
            }
        } else if (strncmp(target, "https://", 8) == 0) {
            http_send_error(client_fd, 400, "Use CONNECT for HTTPS");
            close(client_fd);
            return;
        } else if (target[0] == '/') {
            strncpy(path, target, sizeof(path) - 1);
            path[sizeof(path) - 1] = '\0';
        } else {
            http_send_error(client_fd, 400, "Bad Request");
            close(client_fd);
            return;
        }
    }

    char out_hdr[HTTP_HEADER_MAX + 256];
    int out_len = 0;
    if (!is_connect) {
        out_len = snprintf(out_hdr, sizeof(out_hdr), "%s %s %s\r\n", method, path, version);
        if (out_len <= 0 || out_len >= (int)sizeof(out_hdr)) {
            http_send_error(client_fd, 500, "Proxy Error");
            close(client_fd);
            return;
        }
    }

    const char *p = buf + line_end + 2;
    while (p < buf + header_len) {
        const char *line_start = p;
        const char *line_break = NULL;
        for (const char *q = p; q < buf + header_len - 1; q++) {
            if (q[0] == '\r' && q[1] == '\n') { line_break = q; break; }
        }
        if (!line_break) break;
        int l = (int)(line_break - line_start);
        p = line_break + 2;
        if (l == 0) break;

        if (strncasecmp(line_start, "Host:", 5) == 0) {
            saw_host_hdr = 1;
            if (!host[0]) {
                const char *h = line_start + 5;
                while (*h == ' ' || *h == '\t') h++;
                int hlen = l - (int)(h - line_start);
                if (hlen > 0 && hlen < 256) {
                    char hbuf[256];
                    memcpy(hbuf, h, (size_t)hlen);
                    hbuf[hlen] = '\0';
                    if (parse_hostport(hbuf, host, sizeof(host), &port, 80) != 0) {
                        http_send_error(client_fd, 400, "Bad Host Header");
                        close(client_fd);
                        return;
                    }
                }
            }
        }

        if (!is_connect) {
            if (strncasecmp(line_start, "Proxy-Connection:", 17) == 0) continue;
            if (out_len + l + 2 >= (int)sizeof(out_hdr)) {
                http_send_error(client_fd, 431, "Request Header Fields Too Large");
                close(client_fd);
                return;
            }
            memcpy(out_hdr + out_len, line_start, (size_t)l);
            out_len += l;
            out_hdr[out_len++] = '\r';
            out_hdr[out_len++] = '\n';
        }
    }

    if (!host[0]) {
        http_send_error(client_fd, 400, "Missing Host");
        close(client_fd);
        return;
    }
    if (!is_connect && !saw_host_hdr) {
        int n = snprintf(out_hdr + out_len, sizeof(out_hdr) - (size_t)out_len,
                         "Host: %s\r\n", host);
        if (n <= 0 || out_len + n >= (int)sizeof(out_hdr)) {
            http_send_error(client_fd, 500, "Proxy Error");
            close(client_fd);
            return;
        }
        out_len += n;
    }
    if (!is_connect) {
        if (out_len + 2 >= (int)sizeof(out_hdr)) {
            http_send_error(client_fd, 500, "Proxy Error");
            close(client_fd);
            return;
        }
        out_hdr[out_len++] = '\r';
        out_hdr[out_len++] = '\n';
    }

    if (!is_connect && port == 0) port = 80;

    LOG("HTTP %s %s:%u", method, host, port);

    char rip[INET_ADDRSTRLEN] = {0};
    int remote_fd = connect_outbound(host, port, rip, sizeof(rip));
    if (remote_fd < 0) {
        http_send_error(client_fd, 502, "Bad Gateway");
        close(client_fd);
        return;
    }

    if (is_connect) {
        const char *ok = "HTTP/1.1 200 Connection Established\r\n\r\n";
        write_full(client_fd, ok, strlen(ok));
        int extra = total - header_len;
        if (extra > 0) {
            if (write_full(remote_fd, buf + header_len, (size_t)extra) != extra) {
                close(remote_fd);
                close(client_fd);
                return;
            }
        }
        relay_bidirectional(client_fd, remote_fd, "HTTP-CONNECT", host, port);
        return;
    }

    if (write_full(remote_fd, out_hdr, (size_t)out_len) != out_len) {
        close(remote_fd);
        close(client_fd);
        return;
    }
    int extra = total - header_len;
    if (extra > 0) {
        if (write_full(remote_fd, buf + header_len, (size_t)extra) != extra) {
            close(remote_fd);
            close(client_fd);
            return;
        }
    }

    relay_bidirectional(client_fd, remote_fd, "HTTP", host, port);
}

static int start_listener(int port) {
    int fd = socket(AF_INET, SOCK_STREAM, 0);
    if (fd < 0) { ERR("socket: %s", strerror(errno)); return -1; }

    int opt = 1;
    setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt));
    setsockopt(fd, SOL_SOCKET, SO_REUSEPORT, &opt, sizeof(opt));

    struct sockaddr_in bind_addr = {0};
    bind_addr.sin_family      = AF_INET;
    bind_addr.sin_addr.s_addr = inet_addr("127.0.0.1");
    bind_addr.sin_port        = htons((unsigned short)port);

    if (bind(fd, (struct sockaddr *)&bind_addr, sizeof(bind_addr)) < 0) {
        ERR("bind: %s", strerror(errno));
        close(fd);
        return -1;
    }
    if (listen(fd, 16) < 0) {
        ERR("listen: %s", strerror(errno));
        close(fd);
        return -1;
    }
    return fd;
}

/* ---------- main ---------- */

static void print_usage(const char *prog) {
    fprintf(stderr,
        "Usage: %s [OPTIONS]\n\n"
        "Options:\n"
        "  --port PORT         Listen port (default %d)\n"
        "  --http-proxy PORT   HTTP proxy port (default %d)\n"
        "  --no-http-proxy     Disable HTTP proxy listener\n"
        "  --interface IFACE   Network interface (default auto-detect)\n"
        "  --always-route      Always install scoped default route at startup\n"
        "  --skip-test         Skip initial connectivity test\n"
        "  --help              Show this help\n",
    prog, DEFAULT_PORT, DEFAULT_HTTP_PROXY_PORT);
}

int main(int argc, char *argv[]) {
    int listen_port        = DEFAULT_PORT;
    int http_proxy_port    = DEFAULT_HTTP_PROXY_PORT;
    const char *man_iface  = NULL;
    int skip_test          = 0;

    for (int i = 1; i < argc; i++) {
        if (strcmp(argv[i], "--port") == 0 && i + 1 < argc)
            listen_port = atoi(argv[++i]);
        else if (strcmp(argv[i], "--http-proxy") == 0 && i + 1 < argc)
            http_proxy_port = atoi(argv[++i]);
        else if (strcmp(argv[i], "--no-http-proxy") == 0)
            http_proxy_port = 0;
        else if (strcmp(argv[i], "--interface") == 0 && i + 1 < argc)
            man_iface = argv[++i];
        else if (strcmp(argv[i], "--always-route") == 0)
            g_always_route_setup = 1;
        else if (strcmp(argv[i], "--skip-test") == 0)
            skip_test = 1;
        else if (strcmp(argv[i], "--help") == 0)
            { print_usage(argv[0]); return 0; }
        else
            { fprintf(stderr, "Unknown: %s\n", argv[i]); print_usage(argv[0]); return 1; }
    }

    /* signals */
    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_handler = sig_handler;
    sigaction(SIGTERM, &sa, NULL);
    sigaction(SIGINT,  &sa, NULL);
    signal(SIGCHLD, SIG_IGN);
    signal(SIGPIPE, SIG_IGN);

    LOG("RouterTunnel starting...");

    if (detect_interface(man_iface) < 0) {
        ERR("No suitable network interface. Exiting.");
        return 1;
    }
    LOG("Interface: %s (%s)  index=%d", g_interface, g_local_ip, g_ifindex);

    /* detect gateway once for status/debug output */
    detect_gateway();

    if (g_always_route_setup) {
        auto_setup_route();
    }

    if (!skip_test) {
        if (test_connectivity() != 0) {
            /*
             * On-demand route fallback: only touch routing table if direct
             * IP_BOUND_IF connectivity probe fails.
             */
            if (!g_always_route_setup) {
                LOG("Connectivity failed; attempting scoped route fallback...");
                if (auto_setup_route() == 0 && test_connectivity() == 0) {
                    LOG("Scoped route fallback succeeded");
                } else {
                    LOG("WARNING: connectivity is still failing after route fallback");
                    LOG("Connections may fail until routing is configured.");
                }
            } else {
                LOG("WARNING: connectivity test failed — starting anyway");
                LOG("Connections may fail until routing is configured.");
            }
        }
    }

    /* listen */
    int listen_fd = start_listener(listen_port);
    if (listen_fd < 0) return 1;

    int http_fd = -1;
    if (http_proxy_port > 0) {
        http_fd = start_listener(http_proxy_port);
        if (http_fd < 0) {
            close(listen_fd);
            return 1;
        }
    }

    LOG("==============================================");
    LOG("  RouterTunnel is READY");
    LOG("  Listening on 127.0.0.1:%d", listen_port);
    if (http_fd >= 0)
        LOG("  HTTP proxy on 127.0.0.1:%d", http_proxy_port);
    LOG("  Interface: %s (%s)", g_interface, g_local_ip);
    LOG("  Route setup: %s", g_always_route_setup ? "always-on" : "on-demand fallback");
    LOG("  Traffic will bypass VPN via IP_BOUND_IF");
    LOG("==============================================");

    while (g_running) {
        struct pollfd fds[2];
        int nfds = 0;
        fds[nfds].fd = listen_fd;
        fds[nfds].events = POLLIN;
        nfds++;
        if (http_fd >= 0) {
            fds[nfds].fd = http_fd;
            fds[nfds].events = POLLIN;
            nfds++;
        }

        int ret = poll(fds, nfds, 1000);
        if (ret < 0) { if (errno == EINTR) continue; ERR("poll: %s", strerror(errno)); break; }
        if (ret == 0) continue;
        for (int i = 0; i < nfds; i++) {
            if (!(fds[i].revents & POLLIN)) continue;
            int cfd = accept(fds[i].fd, NULL, NULL);
            if (cfd < 0) { if (errno == EINTR) continue; ERR("accept: %s", strerror(errno)); continue; }

            int is_http = (http_fd >= 0 && fds[i].fd == http_fd);

            if (!is_http) {
                unsigned char first = 0;
                ssize_t n = recv(cfd, &first, 1, MSG_PEEK | MSG_DONTWAIT);
                if (n == 1 && (first == 0x00 || first == 0x01)) {
                    handle_control_client(cfd);
                    continue;
                }
            }

            pid_t pid = fork();
            if (pid < 0) {
                ERR("fork: %s", strerror(errno));
                close(cfd);
            } else if (pid == 0) {
                close(listen_fd);
                if (http_fd >= 0) close(http_fd);
                if (is_http) handle_http_client(cfd);
                else handle_client(cfd);
                _exit(0);
            } else {
                close(cfd);
            }
        }
    }

    LOG("Shutting down.");
    close(listen_fd);
    if (http_fd >= 0) close(http_fd);
    return 0;
}
