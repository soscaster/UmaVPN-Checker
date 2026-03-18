#include <jni.h>

#include <memory>
#include <mutex>
#include <sstream>
#include <string>
#include <thread>
#include <vector>
#include <unistd.h>

#include <client/ovpncli.hpp>

namespace {
std::mutex g_mutex;
std::string g_last_error;
bool g_initialized = false;
bool g_running = false;

JavaVM *g_vm = nullptr;
jclass g_bridge_class = nullptr;
jmethodID g_protect_method = nullptr;
jmethodID g_event_method = nullptr;

bool emit_event_to_java(const std::string &name, const std::string &info, const bool error, const bool fatal) {
    if (g_vm == nullptr || g_bridge_class == nullptr || g_event_method == nullptr) {
        return false;
    }

    JNIEnv *env = nullptr;
    bool attached = false;
    if (g_vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
            return false;
        }
        attached = true;
    }

    jstring jname = env->NewStringUTF(name.c_str());
    jstring jinfo = env->NewStringUTF(info.c_str());
    env->CallStaticVoidMethod(g_bridge_class, g_event_method, jname, jinfo, error ? JNI_TRUE : JNI_FALSE, fatal ? JNI_TRUE : JNI_FALSE);
    env->DeleteLocalRef(jname);
    env->DeleteLocalRef(jinfo);

    if (attached) {
        g_vm->DetachCurrentThread();
    }
    return true;
}

class NativeClient final : public openvpn::ClientAPI::OpenVPNClient {
  public:
    explicit NativeClient(const int tun_fd)
        : master_tun_fd_(tun_fd) {
    }

    ~NativeClient() override {
        if (master_tun_fd_ >= 0) {
            ::close(master_tun_fd_);
            master_tun_fd_ = -1;
        }
    }

    bool tun_builder_new() override {
        reset_tun_builder_state();
        return true;
    }

    bool tun_builder_set_layer(int layer) override {
        // Android VpnService uses L3 tunneling; treat 0 as "unspecified".
        const bool accepted = (layer == 3 || layer == 0);
        if (!accepted) {
            emit_event_to_java("TUN_LAYER_UNSUPPORTED", std::to_string(layer), true, false);
        }
        return accepted;
    }

    bool tun_builder_set_remote_address(const std::string &address, bool ipv6) override {
        remote_address_ = address;
        remote_is_ipv6_ = ipv6;
        return !address.empty();
    }

    bool tun_builder_add_address(const std::string &address,
                                 int prefix_length,
                                 const std::string &gateway,
                                 bool ipv6,
                                 bool net30) override {
        if (!is_prefix_valid(prefix_length, ipv6)) {
            emit_event_to_java("TUN_ADDRESS_INVALID_PREFIX", address + "/" + std::to_string(prefix_length), true, false);
            return false;
        }

        TunAddress value;
        value.address = address;
        value.prefix_length = prefix_length;
        value.gateway = gateway;
        value.ipv6 = ipv6;
        value.net30 = net30;
        addresses_.push_back(std::move(value));
        return true;
    }

    bool tun_builder_set_route_metric_default(int metric) override {
        route_metric_default_ = metric;
        return true;
    }

    bool tun_builder_reroute_gw(bool ipv4, bool ipv6, unsigned int flags) override {
        reroute_ipv4_ = ipv4;
        reroute_ipv6_ = ipv6;
        reroute_flags_ = flags;
        reroute_gw_set_ = true;
        return true;
    }

    bool tun_builder_add_route(const std::string &address,
                               int prefix_length,
                               int metric,
                               bool ipv6) override {
        if (!is_prefix_valid(prefix_length, ipv6)) {
            emit_event_to_java("TUN_ROUTE_INVALID_PREFIX", address + "/" + std::to_string(prefix_length), true, false);
            return false;
        }

        TunRoute value;
        value.address = address;
        value.prefix_length = prefix_length;
        value.metric = metric;
        value.ipv6 = ipv6;
        include_routes_.push_back(std::move(value));
        return true;
    }

    bool tun_builder_exclude_route(const std::string &address,
                                   int prefix_length,
                                   int metric,
                                   bool ipv6) override {
        if (!is_prefix_valid(prefix_length, ipv6)) {
            emit_event_to_java("TUN_EXCLUDE_ROUTE_INVALID_PREFIX", address + "/" + std::to_string(prefix_length), true, false);
            return false;
        }

        TunRoute value;
        value.address = address;
        value.prefix_length = prefix_length;
        value.metric = metric;
        value.ipv6 = ipv6;
        exclude_routes_.push_back(std::move(value));
        return true;
    }

    bool tun_builder_set_dns_options(const openvpn::DnsOptions &dns) override {
        dns_options_ = dns;
        dns_set_ = true;
        return true;
    }

    bool tun_builder_set_mtu(int mtu) override {
        // Android generally supports up to 9000; reject invalid values early.
        if (mtu <= 0 || mtu > 9000) {
            emit_event_to_java("TUN_MTU_INVALID", std::to_string(mtu), true, false);
            return false;
        }
        mtu_ = mtu;
        return true;
    }

    bool tun_builder_set_session_name(const std::string &name) override {
        session_name_ = name;
        return !name.empty();
    }

    bool tun_builder_add_proxy_bypass(const std::string &bypass_host) override {
        proxy_bypass_hosts_.push_back(bypass_host);
        return true;
    }

    bool tun_builder_set_proxy_auto_config_url(const std::string &url) override {
        proxy_auto_config_url_ = url;
        return true;
    }

    bool tun_builder_set_proxy_http(const std::string &host, int port) override {
        proxy_http_ = host + ":" + std::to_string(port);
        return true;
    }

    bool tun_builder_set_proxy_https(const std::string &host, int port) override {
        proxy_https_ = host + ":" + std::to_string(port);
        return true;
    }

    bool tun_builder_add_wins_server(const std::string &address) override {
        wins_servers_.push_back(address);
        return true;
    }

    bool tun_builder_set_allow_family(int af, bool allow) override {
        if (af == 2) {
            allow_family_v4_ = allow;
            allow_family_v4_set_ = true;
            return true;
        }
        if (af == 10) {
            allow_family_v6_ = allow;
            allow_family_v6_set_ = true;
            return true;
        }
        emit_event_to_java("TUN_ALLOW_FAMILY_UNKNOWN", std::to_string(af), false, false);
        return true;
    }

    bool tun_builder_set_allow_local_dns(bool allow) override {
        allow_local_dns_ = allow;
        allow_local_dns_set_ = true;
        return true;
    }

    int tun_builder_establish() override {
        emit_event_to_java("TUN_BUILDER_SUMMARY", tun_builder_summary(), false, false);

        if (master_tun_fd_ < 0) {
            emit_event_to_java("TUN_ESTABLISH_FAILED", "Invalid tun fd", true, true);
            return -1;
        }

        if (addresses_.empty()) {
            emit_event_to_java("TUN_ESTABLISH_WARNING", "No interface addresses were provided by server pushes", false, false);
        }

        const int tun_fd = ::dup(master_tun_fd_);
        if (tun_fd < 0) {
            emit_event_to_java("TUN_ESTABLISH_FAILED", "dup(master_tun_fd) failed", true, true);
            return -1;
        }

        return tun_fd;
    }

    bool tun_builder_persist() override {
        // Disable TUN persistence until state handover is explicitly implemented.
        return false;
    }

    void tun_builder_establish_lite() override {
        emit_event_to_java("TUN_ESTABLISH_LITE", "Requested persisted TUN reuse", false, false);
    }

    void tun_builder_teardown(bool) override {
        reset_tun_builder_state();
    }

    bool pause_on_connection_timeout() override {
        return false;
    }

    bool socket_protect(openvpn_io::detail::socket_type socket, std::string, bool) override {
        if (g_vm == nullptr || g_bridge_class == nullptr || g_protect_method == nullptr) {
            emit_event_to_java("SOCKET_PROTECT", "bridge-not-ready", true, false);
            return false;
        }

        JNIEnv *env = nullptr;
        bool attached = false;
        if (g_vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
            if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
                emit_event_to_java("SOCKET_PROTECT", "attach-thread-failed", true, false);
                return false;
            }
            attached = true;
        }

        const auto result = env->CallStaticBooleanMethod(g_bridge_class, g_protect_method, static_cast<jint>(socket));
        const bool protected_ok = (result == JNI_TRUE);
        emit_event_to_java(
            "SOCKET_PROTECT",
            std::string("fd=") + std::to_string(static_cast<int>(socket)) + (protected_ok ? " ok" : " failed"),
            !protected_ok,
            false
        );
        if (attached) {
            g_vm->DetachCurrentThread();
        }
        return protected_ok;
    }

    void event(const openvpn::ClientAPI::Event &ev) override {
        emit_event_to_java(ev.name, ev.info, ev.error, ev.fatal);

        if (ev.error || ev.fatal || ev.name == "CONNECTED" || ev.name == "DISCONNECTED") {
            std::lock_guard<std::mutex> lock(g_mutex);
            if (ev.error || ev.fatal) {
                g_last_error = ev.name + ": " + ev.info;
            } else {
                g_last_error.clear();
            }
        }
    }

    void acc_event(const openvpn::ClientAPI::AppCustomControlMessageEvent &) override {
    }

    void log(const openvpn::ClientAPI::LogInfo &info) override {
        if (!info.text.empty()) {
            // Forward native OpenVPN logs to UI for reconnect root-cause visibility.
            emit_event_to_java("LOG", info.text, false, false);
        }

        std::lock_guard<std::mutex> lock(g_mutex);
        if (!info.text.empty()) {
            g_last_error = info.text;
        }
    }

    void external_pki_cert_request(openvpn::ClientAPI::ExternalPKICertRequest &req) override {
        req.error = true;
        req.errorText = "External PKI is not implemented";
    }

    void external_pki_sign_request(openvpn::ClientAPI::ExternalPKISignRequest &req) override {
        req.error = true;
        req.errorText = "External PKI is not implemented";
    }

  private:
    struct TunAddress {
        std::string address;
        int prefix_length = 0;
        std::string gateway;
        bool ipv6 = false;
        bool net30 = false;
    };

    struct TunRoute {
        std::string address;
        int prefix_length = 0;
        int metric = -1;
        bool ipv6 = false;
    };

    static bool is_prefix_valid(int prefix_length, bool ipv6) {
        if (prefix_length < 0) {
            return false;
        }
        const int max_prefix = ipv6 ? 128 : 32;
        return prefix_length <= max_prefix;
    }

    std::string tun_builder_summary() const {
        std::ostringstream os;
        os << "remote=" << (remote_address_.empty() ? "<none>" : remote_address_)
           << " (ipv" << (remote_is_ipv6_ ? "6" : "4") << ")"
           << "; session=" << (session_name_.empty() ? "<none>" : session_name_)
           << "; addrs=" << addresses_.size()
           << "; routes=" << include_routes_.size()
           << "; excluded=" << exclude_routes_.size()
           << "; dns_servers=" << dns_options_.servers.size()
           << "; dns_search=" << dns_options_.search_domains.size()
           << "; mtu=" << (mtu_ > 0 ? std::to_string(mtu_) : std::string("<none>"));

        if (reroute_gw_set_) {
            os << "; reroute(v4=" << (reroute_ipv4_ ? "1" : "0")
               << ",v6=" << (reroute_ipv6_ ? "1" : "0")
               << ",flags=" << reroute_flags_ << ")";
        }
        if (route_metric_default_ >= 0) {
            os << "; metric_default=" << route_metric_default_;
        }
        if (allow_family_v4_set_) {
            os << "; allow_af_v4=" << (allow_family_v4_ ? "1" : "0");
        }
        if (allow_family_v6_set_) {
            os << "; allow_af_v6=" << (allow_family_v6_ ? "1" : "0");
        }
        if (allow_local_dns_set_) {
            os << "; allow_local_dns=" << (allow_local_dns_ ? "1" : "0");
        }
        if (!proxy_http_.empty() || !proxy_https_.empty() || !proxy_auto_config_url_.empty() || !proxy_bypass_hosts_.empty()) {
            os << "; proxy_config_present=1";
        }
        if (!wins_servers_.empty()) {
            os << "; wins_servers=" << wins_servers_.size();
        }
        return os.str();
    }

    void reset_tun_builder_state() {
        remote_address_.clear();
        remote_is_ipv6_ = false;
        addresses_.clear();
        include_routes_.clear();
        exclude_routes_.clear();
        dns_options_ = openvpn::DnsOptions();
        dns_set_ = false;
        mtu_ = -1;
        session_name_.clear();
        proxy_bypass_hosts_.clear();
        proxy_auto_config_url_.clear();
        proxy_http_.clear();
        proxy_https_.clear();
        wins_servers_.clear();
        route_metric_default_ = -1;
        reroute_gw_set_ = false;
        reroute_ipv4_ = false;
        reroute_ipv6_ = false;
        reroute_flags_ = 0;
        allow_family_v4_set_ = false;
        allow_family_v6_set_ = false;
        allow_family_v4_ = true;
        allow_family_v6_ = true;
        allow_local_dns_set_ = false;
        allow_local_dns_ = true;
    }

    int master_tun_fd_ = -1;
    std::string remote_address_;
    bool remote_is_ipv6_ = false;

    std::vector<TunAddress> addresses_;
    std::vector<TunRoute> include_routes_;
    std::vector<TunRoute> exclude_routes_;

    openvpn::DnsOptions dns_options_;
    bool dns_set_ = false;
    int mtu_ = -1;
    std::string session_name_;

    std::vector<std::string> proxy_bypass_hosts_;
    std::string proxy_auto_config_url_;
    std::string proxy_http_;
    std::string proxy_https_;
    std::vector<std::string> wins_servers_;

    int route_metric_default_ = -1;
    bool reroute_gw_set_ = false;
    bool reroute_ipv4_ = false;
    bool reroute_ipv6_ = false;
    unsigned int reroute_flags_ = 0;

    bool allow_family_v4_set_ = false;
    bool allow_family_v6_set_ = false;
    bool allow_family_v4_ = true;
    bool allow_family_v6_ = true;
    bool allow_local_dns_set_ = false;
    bool allow_local_dns_ = true;
};

std::unique_ptr<NativeClient> g_client;
std::thread g_connect_thread;

void set_last_error(const std::string &msg) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_last_error = msg;
}

} // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_umavpn_checker_vpn_OpenVpnNativeBridge_nativeInitialize(JNIEnv *env, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);

    if (env->GetJavaVM(&g_vm) != JNI_OK) {
        g_last_error = "Failed to bind JavaVM";
        return JNI_FALSE;
    }

    if (g_bridge_class == nullptr) {
        jclass local_class = env->FindClass("com/umavpn/checker/vpn/OpenVpnNativeBridge");
        if (local_class == nullptr) {
            g_last_error = "Failed to locate OpenVpnNativeBridge class";
            return JNI_FALSE;
        }
        g_bridge_class = reinterpret_cast<jclass>(env->NewGlobalRef(local_class));
        env->DeleteLocalRef(local_class);
    }

    g_protect_method = env->GetStaticMethodID(g_bridge_class, "protectSocket", "(I)Z");
    if (g_protect_method == nullptr) {
        g_last_error = "Failed to resolve protectSocket method";
        return JNI_FALSE;
    }

    g_event_method = env->GetStaticMethodID(g_bridge_class, "onNativeEvent", "(Ljava/lang/String;Ljava/lang/String;ZZ)V");
    if (g_event_method == nullptr) {
        g_last_error = "Failed to resolve onNativeEvent method";
        return JNI_FALSE;
    }

    g_initialized = true;
    g_last_error.clear();
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_umavpn_checker_vpn_OpenVpnNativeBridge_nativeStartSession(JNIEnv *env, jobject,
                                                                    jstring config_text,
                                                                    jint tun_fd) {
    if (config_text == nullptr) {
        set_last_error("Config text is null");
        return JNI_FALSE;
    }
    if (tun_fd < 0) {
        set_last_error("Invalid TUN fd");
        return JNI_FALSE;
    }

    const char *config_chars = env->GetStringUTFChars(config_text, nullptr);
    if (config_chars == nullptr) {
        set_last_error("Failed to read config text");
        return JNI_FALSE;
    }
    const std::string config(config_chars);
    env->ReleaseStringUTFChars(config_text, config_chars);

    if (config.empty()) {
        set_last_error("Empty OpenVPN config");
        return JNI_FALSE;
    }

    {
        std::lock_guard<std::mutex> lock(g_mutex);
        if (!g_initialized) {
            g_last_error = "Native bridge is not initialized";
            return JNI_FALSE;
        }
        if (g_running) {
            g_last_error = "OpenVPN session is already running";
            return JNI_FALSE;
        }

        if (g_connect_thread.joinable()) {
            g_connect_thread.join();
        }

        g_client = std::make_unique<NativeClient>(tun_fd);

        openvpn::ClientAPI::Config client_config;
        client_config.content = config;
        client_config.guiVersion = "UmaVPN Native";
        client_config.clockTickMS = 1000;

        const auto eval = g_client->eval_config(client_config);
        if (eval.error) {
            g_last_error = eval.message.empty() ? "Failed to evaluate OpenVPN config" : eval.message;
            g_client.reset();
            return JNI_FALSE;
        }

        g_running = true;
        g_connect_thread = std::thread([]() {
            NativeClient *client = nullptr;
            {
                std::lock_guard<std::mutex> lock(g_mutex);
                client = g_client.get();
            }

            if (!client) {
                std::lock_guard<std::mutex> lock(g_mutex);
                g_running = false;
                return;
            }

            const openvpn::ClientAPI::Status status = client->connect();

            if (status.error) {
                set_last_error(status.message.empty() ? status.status : status.message);
            }

            std::lock_guard<std::mutex> lock(g_mutex);
            g_running = false;
        });
    }

    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_umavpn_checker_vpn_OpenVpnNativeBridge_nativeStopSession(JNIEnv *, jobject) {
    std::unique_lock<std::mutex> lock(g_mutex);
    if (g_client) {
        g_client->stop();
    }
    lock.unlock();

    if (g_connect_thread.joinable()) {
        if (g_connect_thread.get_id() == std::this_thread::get_id()) {
            // Avoid join deadlock when stop is triggered from callback on connect thread.
            g_connect_thread.detach();
        } else {
            g_connect_thread.join();
        }
    }

    lock.lock();
    g_client.reset();
    g_running = false;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_umavpn_checker_vpn_OpenVpnNativeBridge_nativeIsSessionRunning(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return g_running ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_umavpn_checker_vpn_OpenVpnNativeBridge_nativeGetLastError(JNIEnv *env, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return env->NewStringUTF(g_last_error.c_str());
}
