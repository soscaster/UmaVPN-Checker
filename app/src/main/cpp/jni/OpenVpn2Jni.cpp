// OpenVpn2Jni.cpp
// JNI bridge for the embedded OpenVPN2 engine (ics-openvpn).
//
// Architecture:
//   - openvpn_main(argc, argv) runs in g_vpn_thread (blocking).
//   - OpenVPN creates a Unix domain socket at g_mgmt_sock_path and listens:
//       --management <path> unix
//   - g_mgmt_thread polls for the socket, connects, then drives the protocol:
//       * NEED-OK:PROTECTFD   -> fd comes via SCM_RIGHTS in same recvmsg;
//                                call Java protectSocket2, respond "needok PROTECTFD ok"
//       * NEED-OK:PERSIST_TUN_ACTION -> respond "needok PERSIST_TUN_ACTION NOACTION"
//       * NEED-OK:OPENTUN     -> respond "needok OPENTUN ok" + TUN fd via SCM_RIGHTS
//         (mirrors man_recv_with_fd / man_send_with_fd in ics-openvpn manage.c)
//       * STATE transitions   -> emit events to Java
//   - Thread objects are heap-allocated (raw pointers) so that if openvpn_exit()
//     calls exit(), __cxa_finalize() won't destroy joinable std::thread globals
//     and trigger std::terminate().

#include <jni.h>
#include <android/log.h>

#include <atomic>
#include <cerrno>
#include <cstring>
#include <fstream>
#include <mutex>
#include <sstream>
#include <string>
#include <thread>
#include <vector>

#include <setjmp.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <sys/un.h>
#include <unistd.h>

extern "C" {
int openvpn_main(int argc, char *argv[]);
// __wrap_openvpn_exit is defined below and wired via -Wl,--wrap=openvpn_exit.
// It prevents openvpn_exit() from calling exit() and killing the process.
}

#define OPVN2_TAG "OpenVpn2Jni"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, OPVN2_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  OPVN2_TAG, __VA_ARGS__)

namespace opvn2 {

// ---------------------------------------------------------------------------
// Global state
// ---------------------------------------------------------------------------
std::mutex  g_mutex;
std::string g_last_error;

std::atomic<bool> g_initialized{false};
std::atomic<bool> g_running{false};
std::atomic<bool> g_stop_requested{false};

JavaVM    *g_vm             = nullptr;
jclass     g_bridge_class   = nullptr;
jmethodID  g_protect_method = nullptr;
jmethodID  g_event_method   = nullptr;

// Connected fd on the management socket (our side, set by mgmt thread).
std::atomic<int> g_mgmt_our_fd{-1};

// Pre-opened TUN fd — given to OpenVPN on NEED-OK:OPENTUN via SCM_RIGHTS.
int g_tun_fd = -1;

// Monotonic session counter for unique temp file names.
static int g_session_id = 0;
std::string g_config_path;
std::string g_mgmt_sock_path;

// Server-assigned TUN config, populated from NEED-OK:IFCONFIG / NEED-OK:DNSSERVER.
std::string g_pending_tun_ip;
int         g_pending_tun_prefix{24};
std::string g_pending_dns_server;
jmethodID   g_open_tunnel_method = nullptr;

// Heap-allocated thread objects: pointer so __cxa_finalize() sees trivial destructor.
std::thread *g_vpn_thread  = nullptr;
std::thread *g_mgmt_thread = nullptr;

// setjmp target for __wrap_openvpn_exit.  Valid only while openvpn_main() is
// on the VPN-thread call stack.  Allows openvpn_exit() to return (via longjmp)
// instead of calling exit() and killing the process.
jmp_buf           g_vpn_exit_jmp;
std::atomic<bool> g_vpn_jmp_valid{false};

// ---------------------------------------------------------------------------
// Emit a callback event to Java.
// ---------------------------------------------------------------------------
static void emit_event(const std::string &name, const std::string &info,
                       bool error, bool fatal) {
    if (!g_vm || !g_bridge_class || !g_event_method) return;

    JNIEnv *env = nullptr;
    bool attached = false;
    if (g_vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
        attached = true;
    }

    jstring jname = env->NewStringUTF(name.c_str());
    jstring jinfo = env->NewStringUTF(info.c_str());
    env->CallStaticVoidMethod(g_bridge_class, g_event_method,
                              jname, jinfo,
                              error ? JNI_TRUE : JNI_FALSE,
                              fatal ? JNI_TRUE : JNI_FALSE);
    env->DeleteLocalRef(jname);
    env->DeleteLocalRef(jinfo);

    if (attached) g_vm->DetachCurrentThread();
}

// ---------------------------------------------------------------------------
// Call Java protectSocket2(fd).
// ---------------------------------------------------------------------------
static bool call_protect_socket(int fd) {
    if (!g_vm || !g_bridge_class || !g_protect_method) return false;

    JNIEnv *env = nullptr;
    bool attached = false;
    if (g_vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return false;
        attached = true;
    }

    jboolean result = env->CallStaticBooleanMethod(g_bridge_class, g_protect_method,
                                                   static_cast<jint>(fd));
    if (attached) g_vm->DetachCurrentThread();
    return (result == JNI_TRUE);
}

// ---------------------------------------------------------------------------
// Convert dotted-decimal mask ("255.255.255.0") to prefix length (24).
// ---------------------------------------------------------------------------
static int mask_to_prefix(const std::string &mask) {
    int bits = 0;
    std::istringstream ss(mask);
    std::string octet;
    while (std::getline(ss, octet, '.')) {
        unsigned val = 0;
        try { val = static_cast<unsigned>(std::stoul(octet)); } catch (...) {}
        for (int i = 7; i >= 0; --i) { if (val & (1u << i)) ++bits; else break; }
    }
    return bits > 0 ? bits : 24;
}

// ---------------------------------------------------------------------------
// Synchronously call Java OpenVpn2NativeBridge.openTunnelForIp(ip,prefix,dns).
// Returns the new TUN fd, or -1 on failure.
// ---------------------------------------------------------------------------
static int call_open_tunnel(const std::string &ip, int prefix, const std::string &dns) {
    if (!g_vm || !g_bridge_class || !g_open_tunnel_method) return -1;
    JNIEnv *env = nullptr;
    bool attached = false;
    if (g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return -1;
        attached = true;
    }
    jstring jip  = env->NewStringUTF(ip.c_str());
    jstring jdns = env->NewStringUTF(dns.c_str());
    jint fd = env->CallStaticIntMethod(g_bridge_class, g_open_tunnel_method,
                                       jip, static_cast<jint>(prefix), jdns);
    env->DeleteLocalRef(jip);
    env->DeleteLocalRef(jdns);
    if (attached) g_vm->DetachCurrentThread();
    return static_cast<int>(fd);
}

// ---------------------------------------------------------------------------
// Send text + optional fd via sendmsg/SCM_RIGHTS.
// Mirrors man_send_with_fd() in ics-openvpn manage.c.
// Used to pass the TUN fd to OpenVPN in response to NEED-OK:OPENTUN.
// ---------------------------------------------------------------------------
static bool mgmt_send_with_fd(int sock, const std::string &text, int fd_to_send) {
    struct iovec iov;
    iov.iov_base = const_cast<char *>(text.c_str());
    iov.iov_len  = text.size();

    union {
        char buf[CMSG_SPACE(sizeof(int))];
        struct cmsghdr align;
    } ctrl_buf;

    struct msghdr msg;
    std::memset(&msg, 0, sizeof(msg));
    msg.msg_iov    = &iov;
    msg.msg_iovlen = 1;

    if (fd_to_send >= 0) {
        std::memset(ctrl_buf.buf, 0, sizeof(ctrl_buf.buf));
        msg.msg_control    = ctrl_buf.buf;
        msg.msg_controllen = sizeof(ctrl_buf.buf);

        struct cmsghdr *cmsg = CMSG_FIRSTHDR(&msg);
        cmsg->cmsg_len   = CMSG_LEN(sizeof(int));
        cmsg->cmsg_level = SOL_SOCKET;
        cmsg->cmsg_type  = SCM_RIGHTS;
        std::memcpy(CMSG_DATA(cmsg), &fd_to_send, sizeof(int));
    }

    return (::sendmsg(sock, &msg, MSG_NOSIGNAL) >= 0);
}

// ---------------------------------------------------------------------------
// Write a plain text management command (no fd attachment).
// ---------------------------------------------------------------------------
static void mgmt_write(const std::string &line) {
    int fd = g_mgmt_our_fd.load();
    if (fd < 0) return;
    const std::string cmd = line + "\r\n";
    ::write(fd, cmd.c_str(), cmd.size());
}

// ---------------------------------------------------------------------------
// Parse a single management interface line from OpenVPN.
// Returns false to stop the reader loop.
// ---------------------------------------------------------------------------
static bool process_mgmt_line(const std::string &line) {
    if (line.empty()) return true;

    LOGI("[OPVN2] mgmt< %s", line.c_str());

    // ---- NEED-OK requests --------------------------------------------------
    // ics-openvpn format: >NEED-OK:Need '<TYPE>' confirmation MSG:<detail>
    // (manage.c: msg(M_CLIENT, ">NEED-OK:Need '%s' confirmation MSG:%s", type, string))
    const bool is_need_ok = (line.rfind(">NEED-OK:", 0) == 0);

    // IFCONFIG: parse server-assigned VPN IP and mask for TUN creation at OPENTUN time.
    // Format: >NEED-OK:Need 'IFCONFIG' confirmation MSG:ip netmask
    if (is_need_ok && line.find("'IFCONFIG'") != std::string::npos) {
        size_t msg = line.find("MSG:");
        if (msg != std::string::npos) {
            std::string rest = line.substr(msg + 4);
            while (!rest.empty() && (rest.back() == '\r' || rest.back() == '\n')) rest.pop_back();
            size_t sp = rest.find(' ');
            g_pending_tun_ip = (sp != std::string::npos) ? rest.substr(0, sp) : rest;
            if (sp != std::string::npos) {
                std::string mask = rest.substr(sp + 1);
                size_t sp2 = mask.find(' ');
                if (sp2 != std::string::npos) mask = mask.substr(0, sp2);
                g_pending_tun_prefix = mask_to_prefix(mask);
            }
        }
        mgmt_write("needok IFCONFIG ok");
        emit_event("LOG", "[mgmt] sent needok IFCONFIG ok (tun-ip=" + g_pending_tun_ip + ")", false, false);
        return true;
    }

    // DNSSERVER: capture the first pushed DNS server for TUN configuration.
    // Format: >NEED-OK:Need 'DNSSERVER' confirmation MSG:dns_ip
    if (is_need_ok && line.find("'DNSSERVER'") != std::string::npos) {
        size_t msg = line.find("MSG:");
        if (msg != std::string::npos && g_pending_dns_server.empty()) {
            g_pending_dns_server = line.substr(msg + 4);
            while (!g_pending_dns_server.empty() &&
                   (g_pending_dns_server.back() == '\r' || g_pending_dns_server.back() == '\n' ||
                    g_pending_dns_server.back() == ' '))
                g_pending_dns_server.pop_back();
        }
        mgmt_write("needok DNSSERVER ok");
        emit_event("LOG", "[mgmt] sent needok DNSSERVER ok", false, false);
        return true;
    }

    // TUN open: create a correctly-configured TUN (server-assigned IP) and send via SCM_RIGHTS.
    // (mirrors man_recv_with_fd in ics-openvpn manage.c)
    if (is_need_ok && line.find("'OPENTUN'") != std::string::npos) {
        int tfd = -1;
        if (!g_pending_tun_ip.empty() && g_open_tunnel_method) {
            tfd = call_open_tunnel(g_pending_tun_ip, g_pending_tun_prefix, g_pending_dns_server);
            LOGI("[OPVN2] OPENTUN: openTunnelForIp(%s/%d dns=%s) -> fd=%d",
                 g_pending_tun_ip.c_str(), g_pending_tun_prefix,
                 g_pending_dns_server.c_str(), tfd);
        }
        if (tfd < 0) {
            // Fallback: use the pre-created TUN (wrong IP, but prevents hard crash).
            tfd = g_tun_fd;
            LOGI("[OPVN2] OPENTUN: no server IP from IFCONFIG, falling back to pre-created fd=%d", tfd);
        }
        int sock = g_mgmt_our_fd.load();
        const std::string resp = "needok OPENTUN ok\r\n";
        if (tfd >= 0 && sock >= 0 && mgmt_send_with_fd(sock, resp, tfd)) {
            LOGI("[OPVN2] OPENTUN: sent fd=%d via SCM_RIGHTS", tfd);
            emit_event("LOG", "[mgmt] sent OPENTUN tun-fd=" + std::to_string(tfd) + " via SCM_RIGHTS", false, false);
        } else {
            LOGE("[OPVN2] OPENTUN: failed (tfd=%d sock=%d)", tfd, sock);
            emit_event("TUN_FD_SEND_FAILED", "OPENTUN SCM_RIGHTS failed", true, true);
            mgmt_write("needok OPENTUN error");
        }
        return true;
    }

    // Persist-tun: NOACTION = open a new TUN.
    if (is_need_ok && line.find("'PERSIST_TUN_ACTION'") != std::string::npos) {
        mgmt_write("needok PERSIST_TUN_ACTION NOACTION");
        emit_event("LOG", "[mgmt] sent needok PERSIST_TUN_ACTION NOACTION", false, false);
        return true;
    }

    // Socket protect: fd arrived via SCM_RIGHTS in the same recvmsg that
    // delivered this text line; already protected in mgmt_reader_thread_fn.
    if (is_need_ok && line.find("'PROTECTFD'") != std::string::npos) {
        mgmt_write("needok PROTECTFD ok");
        emit_event("LOG", "[mgmt] sent needok PROTECTFD ok", false, false);
        return true;
    }

    // Catch-all for any other NEED-OK (IFCONFIG6, ROUTE, ROUTE6, DNS, …).
    if (is_need_ok) {
        size_t q1 = line.find('\'');
        size_t q2 = (q1 != std::string::npos) ? line.find('\'', q1 + 1) : std::string::npos;
        if (q1 != std::string::npos && q2 != std::string::npos) {
            std::string ntype = line.substr(q1 + 1, q2 - q1 - 1);
            mgmt_write("needok " + ntype + " ok");
            emit_event("LOG", "[mgmt] sent needok " + ntype + " ok", false, false);
        }
        return true;
    }

    // ---- Credential prompt -------------------------------------------------
    // Format: >PASSWORD:Need 'Auth' username/password
    //         >PASSWORD:Need 'Private Key' password
    // Sent when the config has auth-user-pass and --management-query-passwords
    // is active.  VPNGate/opengw.net servers accept any credentials.
    if (line.rfind(">PASSWORD:", 0) == 0) {
        // Extract credential type from between single quotes.
        std::string cred_type = "Auth";
        size_t q1 = line.find('\'');
        size_t q2 = (q1 != std::string::npos) ? line.find('\'', q1 + 1) : std::string::npos;
        if (q1 != std::string::npos && q2 != std::string::npos) {
            cred_type = line.substr(q1 + 1, q2 - q1 - 1);
        }
        bool needs_username = (line.find("username/password") != std::string::npos);
        if (needs_username) {
            mgmt_write("username \"" + cred_type + "\" \"vpn\"");
            mgmt_write("password \"" + cred_type + "\" \"vpn\"");
        } else {
            // Private Key passphrase or similar — send empty.
            mgmt_write("password \"" + cred_type + "\" \"\"");
        }
        emit_event("LOG", "[mgmt] sent credentials for '" + cred_type + "'", false, false);
        return true;
    }

    // ---- State transitions -------------------------------------------------
    // Format: >STATE:unix_ts,STATE_NAME,detail,...
    if (line.rfind(">STATE:", 0) == 0) {
        std::string rest = line.substr(7);
        std::string state_name, detail;
        size_t c1 = rest.find(',');
        if (c1 != std::string::npos) {
            rest = rest.substr(c1 + 1);
            size_t c2 = rest.find(',');
            if (c2 != std::string::npos) {
                state_name = rest.substr(0, c2);
                detail     = rest.substr(c2 + 1);
            } else {
                state_name = rest;
            }
        } else {
            state_name = rest;
        }

        auto rstrip = [](std::string &s) {
            while (!s.empty() &&
                   (s.back() == '\r' || s.back() == '\n' || s.back() == ' '))
                s.pop_back();
        };
        rstrip(state_name);
        rstrip(detail);

        if (state_name == "CONNECTED") {
            emit_event("CONNECTED", detail, false, false);
        } else if (state_name == "EXITING" || state_name == "DISCONNECTED") {
            emit_event("DISCONNECTED", detail, false, false);
            g_running.store(false);
            return false;  // stop reader loop
        } else if (state_name == "RECONNECTING") {
            emit_event("RECONNECTING", detail, false, false);
        } else {
            emit_event(state_name, detail, false, false);
        }
        return true;
    }

    // ---- Log messages ------------------------------------------------------
    // Format: >LOG:unix_ts,FLAGS,text
    if (line.rfind(">LOG:", 0) == 0) {
        std::string rest = line.substr(5);
        size_t c1 = rest.find(',');
        if (c1 != std::string::npos) {
            rest = rest.substr(c1 + 1);
            size_t c2 = rest.find(',');
            if (c2 != std::string::npos) rest = rest.substr(c2 + 1);
        }
        while (!rest.empty() && (rest.back() == '\r' || rest.back() == '\n'))
            rest.pop_back();
        emit_event("LOG", rest, false, false);
        return true;
    }

    // ---- Fatal errors ------------------------------------------------------
    if (line.rfind(">FATAL:", 0) == 0) {
        std::string m = line.substr(7);
        while (!m.empty() && (m.back() == '\r' || m.back() == '\n')) m.pop_back();
        { std::lock_guard<std::mutex> lock(g_mutex); g_last_error = m; }
        emit_event("FATAL", m, true, true);
        g_running.store(false);
        return false;
    }

    // ---- Generic error lines -----------------------------------------------
    if (line.rfind(">ERROR:", 0) == 0) {
        std::string m = line.substr(7);
        while (!m.empty() && (m.back() == '\r' || m.back() == '\n')) m.pop_back();
        { std::lock_guard<std::mutex> lock(g_mutex); g_last_error = m; }
        emit_event("ERROR", m, true, false);
        return true;
    }

    // ---- Catch-all: emit any unhandled '>' notification --------------------
    // Every OpenVPN management notification starts with '>'.  Forward any
    // unrecognised ones to the app log so stuck sessions can be diagnosed.
    if (!line.empty() && line[0] == '>') {
        emit_event("LOG", "[mgmt] unhandled: " + line, false, false);
    }

    return true;
}

// ---------------------------------------------------------------------------
// Management reader thread.
//
// 1. Polls for OpenVPN's Unix socket to appear (OpenVPN creates it on startup).
// 2. Connects and stores the fd in g_mgmt_our_fd.
// 3. Reads using recvmsg so that SCM_RIGHTS fds (PROTECTFD) are captured.
// 4. For each received fd via SCM_RIGHTS: calls Java protectSocket2.
// 5. Processes complete text lines via process_mgmt_line.
// ---------------------------------------------------------------------------
static void mgmt_reader_thread_fn(std::string sock_path) {
    // Poll for OpenVPN to create the socket (max 10 s, 100 ms intervals).
    // Also abort immediately if openvpn_main exits before creating the socket.
    int conn_fd = -1;
    for (int i = 0; i < 100 && !g_stop_requested.load() && g_running.load(); ++i) {
        conn_fd = ::socket(AF_UNIX, SOCK_STREAM, 0);
        if (conn_fd < 0) { ::usleep(100000); continue; }

        struct sockaddr_un addr{};
        addr.sun_family = AF_UNIX;
        std::strncpy(addr.sun_path, sock_path.c_str(), sizeof(addr.sun_path) - 1);

        if (::connect(conn_fd,
                      reinterpret_cast<struct sockaddr *>(&addr),
                      sizeof(addr)) == 0) {
            break;  // connected
        }
        ::close(conn_fd);
        conn_fd = -1;
        ::usleep(100000);
    }

    if (conn_fd < 0) {
        LOGE("[OPVN2] mgmt: could not connect to %s", sock_path.c_str());
        emit_event("DISCONNECTED", "mgmt-connect-failed", true, true);
        g_running.store(false);
        return;
    }

    g_mgmt_our_fd.store(conn_fd);
    LOGI("[OPVN2] mgmt connected to %s (fd=%d)", sock_path.c_str(), conn_fd);
    emit_event("LOG", "[mgmt] connected to management socket", false, false);

    // Without these, OpenVPN2 only sends NEED-OK prompts but not LOG/STATE.
    // "state on"  → real-time >STATE: events (needed for CONNECTED detection).
    // "log all"   → real-time >LOG: events (needed for diagnostics).
    mgmt_write("state on");
    mgmt_write("log all");

    std::string buf;
    char data[256];

    while (!g_stop_requested.load()) {
        // Use recvmsg to capture any SCM_RIGHTS fd bundled by OpenVPN (PROTECTFD).
        union {
            char ctrl[CMSG_SPACE(sizeof(int))];
            struct cmsghdr align;
        } ctrl_buf;

        struct iovec iov;
        iov.iov_base = data;
        iov.iov_len  = sizeof(data) - 1;

        struct msghdr mhdr{};
        mhdr.msg_iov        = &iov;
        mhdr.msg_iovlen     = 1;
        mhdr.msg_control    = ctrl_buf.ctrl;
        mhdr.msg_controllen = sizeof(ctrl_buf.ctrl);

        ssize_t n = ::recvmsg(conn_fd, &mhdr, 0);
        if (n <= 0) {
            if (g_running.load()) {
                emit_event("DISCONNECTED", "mgmt-eof", false, false);
                g_running.store(false);
            }
            break;
        }
        data[n] = '\0';
        buf += data;

        // Extract any fd received via SCM_RIGHTS (PROTECTFD mechanism).
        struct cmsghdr *cmsg = CMSG_FIRSTHDR(&mhdr);
        if (cmsg &&
            cmsg->cmsg_level == SOL_SOCKET &&
            cmsg->cmsg_type  == SCM_RIGHTS &&
            cmsg->cmsg_len   == CMSG_LEN(sizeof(int))) {
            int received_fd = -1;
            std::memcpy(&received_fd, CMSG_DATA(cmsg), sizeof(int));
            if (received_fd >= 0) {
                bool ok = call_protect_socket(received_fd);
                ::close(received_fd);  // close our duplicate after protecting
                LOGI("[OPVN2] PROTECTFD: fd %d %s", received_fd,
                     ok ? "protected" : "FAILED");
            }
        }

        // Process complete lines from the text buffer.
        size_t pos;
        while ((pos = buf.find('\n')) != std::string::npos) {
            std::string line = buf.substr(0, pos);
            buf.erase(0, pos + 1);
            if (!line.empty() && line.back() == '\r') line.pop_back();
            if (!process_mgmt_line(line)) goto done;
        }
    }
done:
    ::close(conn_fd);
    g_mgmt_our_fd.store(-1);
    LOGI("[OPVN2] mgmt_reader_thread exiting");
}

// ---------------------------------------------------------------------------
// Helper: close and reset an fd.
// ---------------------------------------------------------------------------
static void safe_close_atomic(std::atomic<int> &afd) {
    int fd = afd.exchange(-1);
    if (fd >= 0) ::close(fd);
}

// ---------------------------------------------------------------------------
// Cleanup after a session ends or before starting a new one.
// ---------------------------------------------------------------------------
static void cleanup_session() {
    g_stop_requested.store(true);

    // Signal OpenVPN to exit via management socket.
    int mfd = g_mgmt_our_fd.load();
    if (mfd >= 0) {
        const char *sigterm = "signal SIGTERM\r\n";
        ::write(mfd, sigterm, std::strlen(sigterm));
    }
    // Close our side to unblock recvmsg in the management reader thread.
    safe_close_atomic(g_mgmt_our_fd);

    // Join management reader thread.
    if (g_mgmt_thread) {
        if (g_mgmt_thread->joinable()) g_mgmt_thread->join();
        delete g_mgmt_thread;
        g_mgmt_thread = nullptr;
    }

    // Join VPN thread (wait for openvpn_main to return).
    if (g_vpn_thread) {
        if (g_vpn_thread->get_id() == std::this_thread::get_id()) {
            // Called from within the VPN thread itself — just detach.
            g_vpn_thread->detach();
        } else if (g_vpn_thread->joinable()) {
            g_vpn_thread->join();
        }
        delete g_vpn_thread;
        g_vpn_thread = nullptr;
    }

    // Clean up temp files.
    if (!g_config_path.empty()) {
        ::unlink(g_config_path.c_str());
        g_config_path.clear();
    }
    if (!g_mgmt_sock_path.empty()) {
        ::unlink(g_mgmt_sock_path.c_str());
        g_mgmt_sock_path.clear();
    }

    g_running.store(false);
    g_stop_requested.store(false);

    if (g_tun_fd >= 0) { ::close(g_tun_fd); g_tun_fd = -1; }

    g_pending_tun_ip.clear();
    g_pending_tun_prefix = 24;
    g_pending_dns_server.clear();
}

// ---------------------------------------------------------------------------
// Trampoline: isolated C function so no C++ objects sit between setjmp and
// the longjmp in __wrap_openvpn_exit.  Stack-unwinding via longjmp is safe
// here because openvpn_main() and everything it calls is plain C.
// ---------------------------------------------------------------------------
static int run_openvpn_main_with_longjmp(int argc, char **argv) {
    g_vpn_jmp_valid.store(true);
    int code = setjmp(g_vpn_exit_jmp);
    if (code == 0) {
        code = openvpn_main(argc, argv);
    } else {
        LOGI("[OPVN2] openvpn_exit intercepted (code=%d), session ending cleanly", code);
    }
    g_vpn_jmp_valid.store(false);
    return code;
}

} // namespace opvn2

// ===========================================================================
// Linker wrap: all calls to openvpn_exit() are redirected here by
// -Wl,--wrap=openvpn_exit in CMakeLists.txt.  Prevents exit() from killing
// the Android process when OpenVPN shuts down (e.g. on SIGTERM / disconnect).
// ===========================================================================
extern "C" void __wrap_openvpn_exit(int status) {
    if (opvn2::g_vpn_jmp_valid.exchange(false)) {
        longjmp(opvn2::g_vpn_exit_jmp, status == 0 ? 1 : status);
    }
    // Called outside a valid session (e.g. during init failure before setjmp).
    __android_log_print(ANDROID_LOG_WARN, OPVN2_TAG,
                        "[OPVN2] __wrap_openvpn_exit(%d): no jmp_buf, returning", status);
}

// ===========================================================================
// JNI Exports
// ===========================================================================

extern "C" JNIEXPORT jboolean JNICALL
Java_com_umavpn_checker_vpn_OpenVpn2NativeBridge_nativeInitialize2(JNIEnv *env, jobject) {
    using namespace opvn2;
    std::lock_guard<std::mutex> lock(g_mutex);

    if (env->GetJavaVM(&g_vm) != JNI_OK) {
        g_last_error = "[OPVN2] Failed to bind JavaVM";
        return JNI_FALSE;
    }

    if (!g_bridge_class) {
        jclass local = env->FindClass("com/umavpn/checker/vpn/OpenVpn2NativeBridge");
        if (!local) {
            g_last_error = "[OPVN2] Failed to locate OpenVpn2NativeBridge class";
            return JNI_FALSE;
        }
        g_bridge_class = reinterpret_cast<jclass>(env->NewGlobalRef(local));
        env->DeleteLocalRef(local);
    }

    g_protect_method = env->GetStaticMethodID(g_bridge_class, "protectSocket2", "(I)Z");
    if (!g_protect_method) {
        g_last_error = "[OPVN2] Failed to resolve protectSocket2";
        return JNI_FALSE;
    }

    g_event_method = env->GetStaticMethodID(
        g_bridge_class, "onNativeEvent2",
        "(Ljava/lang/String;Ljava/lang/String;ZZ)V");
    if (!g_event_method) {
        g_last_error = "[OPVN2] Failed to resolve onNativeEvent2";
        return JNI_FALSE;
    }

    g_open_tunnel_method = env->GetStaticMethodID(
        g_bridge_class, "openTunnelForIp",
        "(Ljava/lang/String;ILjava/lang/String;)I");
    if (!g_open_tunnel_method) {
        g_last_error = "[OPVN2] Failed to resolve openTunnelForIp";
        return JNI_FALSE;
    }

    g_initialized.store(true);
    g_last_error.clear();
    LOGI("[OPVN2] initialized");
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_umavpn_checker_vpn_OpenVpn2NativeBridge_nativeStartSession2(
        JNIEnv *env, jobject,
        jstring j_config_text, jint j_tun_fd, jstring j_cache_dir) {
    using namespace opvn2;

    if (!j_config_text || !j_cache_dir) {
        std::lock_guard<std::mutex> lock(g_mutex);
        g_last_error = "[OPVN2] Null config or cache dir parameter";
        return JNI_FALSE;
    }
    if ((int)j_tun_fd < 0) {
        std::lock_guard<std::mutex> lock(g_mutex);
        g_last_error = "[OPVN2] Invalid TUN fd";
        return JNI_FALSE;
    }

    const char *config_chars = env->GetStringUTFChars(j_config_text, nullptr);
    const char *cache_chars  = env->GetStringUTFChars(j_cache_dir,   nullptr);
    if (!config_chars || !cache_chars) {
        if (config_chars) env->ReleaseStringUTFChars(j_config_text, config_chars);
        if (cache_chars)  env->ReleaseStringUTFChars(j_cache_dir,   cache_chars);
        std::lock_guard<std::mutex> lock(g_mutex);
        g_last_error = "[OPVN2] Failed to read string parameters";
        return JNI_FALSE;
    }

    const std::string config_text(config_chars);
    const std::string cache_dir(cache_chars);
    env->ReleaseStringUTFChars(j_config_text, config_chars);
    env->ReleaseStringUTFChars(j_cache_dir,   cache_chars);

    if (config_text.empty()) {
        std::lock_guard<std::mutex> lock(g_mutex);
        g_last_error = "[OPVN2] Empty config text";
        return JNI_FALSE;
    }

    {
        std::lock_guard<std::mutex> lock(g_mutex);
        if (!g_initialized.load()) {
            g_last_error = "[OPVN2] Not initialized";
            return JNI_FALSE;
        }
        if (g_running.load()) {
            g_last_error = "[OPVN2] Session already running";
            return JNI_FALSE;
        }
    }

    // Clean up any previous session artifacts.
    cleanup_session();

    // -----------------------------------------------------------------------
    // 1. Set up file paths.
    // -----------------------------------------------------------------------
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        g_session_id++;
        g_config_path    = cache_dir + "/uma_openvpn2_"   + std::to_string(g_session_id) + ".conf";
        g_mgmt_sock_path = cache_dir + "/uma_ovpn2_mgmt_" + std::to_string(g_session_id) + ".sock";
    }

    // Remove stale socket file if present.
    ::unlink(g_mgmt_sock_path.c_str());

    // -----------------------------------------------------------------------
    // 2. Write config to a temporary file.
    // -----------------------------------------------------------------------
    {
        std::ofstream f(g_config_path.c_str(), std::ios::out | std::ios::trunc);
        if (!f.is_open()) {
            std::lock_guard<std::mutex> lock(g_mutex);
            g_last_error = "[OPVN2] Failed to write temp config: " + g_config_path;
            return JNI_FALSE;
        }
        f << config_text;
    }
    LOGI("[OPVN2] config written to %s", g_config_path.c_str());

    {
        std::lock_guard<std::mutex> lock(g_mutex);
        g_tun_fd = (int)j_tun_fd;
        g_stop_requested.store(false);
        g_running.store(true);
    }

    // -----------------------------------------------------------------------
    // 3. Build argv for openvpn_main().
    //    --management <path> unix  makes OpenVPN listen on a Unix domain socket.
    //    Our mgmt thread connects to it once it appears.
    // -----------------------------------------------------------------------
    std::vector<std::string> arg_strings = {
        "openvpn",
        "--config",                    g_config_path,
        "--management",                g_mgmt_sock_path, "unix",
        "--management-query-passwords",
        "--dev",                       "tun",
        "--script-security",           "0",
        "--client",
    };

    const int argc = static_cast<int>(arg_strings.size());
    LOGI("[OPVN2] starting openvpn_main: %d args, mgmt=%s, tun-fd=%d",
         argc, g_mgmt_sock_path.c_str(), (int)j_tun_fd);

    // -----------------------------------------------------------------------
    // 4. Launch management reader thread (connects to OpenVPN's socket).
    // -----------------------------------------------------------------------
    std::string sock_path_copy = g_mgmt_sock_path;
    g_mgmt_thread = new std::thread([sock_path_copy]() {
        opvn2::mgmt_reader_thread_fn(sock_path_copy);
    });

    // -----------------------------------------------------------------------
    // 5. Launch VPN thread (blocking call to openvpn_main).
    // -----------------------------------------------------------------------
    std::vector<std::string> arg_copy(arg_strings);
    g_vpn_thread = new std::thread([arg_copy]() mutable {
        std::vector<char *> av;
        av.reserve(arg_copy.size() + 1);
        for (auto &s : arg_copy) av.push_back(&s[0]);
        av.push_back(nullptr);

        // run_openvpn_main_with_longjmp sets up setjmp so that
        // __wrap_openvpn_exit can longjmp back instead of calling exit().
        int ret = opvn2::run_openvpn_main_with_longjmp(
                      static_cast<int>(arg_copy.size()), av.data());
        LOGI("[OPVN2] openvpn_main returned %d", ret);
        opvn2::g_running.store(false);
    });

    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_umavpn_checker_vpn_OpenVpn2NativeBridge_nativeStopSession2(JNIEnv *, jobject) {
    using namespace opvn2;
    LOGI("[OPVN2] nativeStopSession2 called");
    cleanup_session();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_umavpn_checker_vpn_OpenVpn2NativeBridge_nativeIsSessionRunning2(JNIEnv *, jobject) {
    return opvn2::g_running.load() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_umavpn_checker_vpn_OpenVpn2NativeBridge_nativeGetLastError2(JNIEnv *env, jobject) {
    std::lock_guard<std::mutex> lock(opvn2::g_mutex);
    return env->NewStringUTF(opvn2::g_last_error.c_str());
}
