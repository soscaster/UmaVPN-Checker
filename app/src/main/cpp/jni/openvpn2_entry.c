/*
 * openvpn2_entry.c
 *
 * openvpn_main() is declared `static` in openvpn.c, so it has internal linkage
 * and cannot be called from OpenVpn2Jni.cpp (a separate translation unit).
 *
 * This file replaces openvpn.c in the build:
 *   1. #define openvpn_main to a private renamed symbol before including openvpn.c.
 *      This makes the static function's name unique but still private to this TU.
 *   2. After the include, we define a public (non-static) openvpn_main() that
 *      delegates to the private renamed function.
 *
 * Because openvpn.c is included (not compiled separately), there is no
 * duplicate-symbol issue. The openvpn.c file must be removed from OPENVPN2_SRCS
 * in CMakeLists.txt to prevent double compilation.
 */

/* Rename the static function before it is defined by openvpn.c */
#define openvpn_main _uma_openvpn2_main_internal_

/* Include the full openvpn.c source — compiles in THIS translation unit */
#include "../vendor/ics-openvpn/main/src/main/cpp/openvpn/src/openvpn/openvpn.c"

#undef openvpn_main

/*
 * Public entry point — non-static, externally visible.
 * OpenVpn2Jni.cpp declares: extern "C" { int openvpn_main(int argc, char *argv[]); }
 */
int openvpn_main(int argc, char *argv[])
{
    /*
     * Reset stale global signal state from a previous longjmp-terminated session.
     *
     * After disconnect, openvpn calls close_management() then openvpn_exit().
     * __wrap_openvpn_exit intercepts via longjmp, so openvpn never reaches
     * uninit_static() — which does not clear siginfo_static anyway.
     *
     * Result: siginfo_static.signal_received == SIGTERM persists into the next
     * call. openvpn_main() assigns c.sig = &siginfo_static, then IS_SIG(&c) is
     * immediately true in tunnel_point_to_point(), causing openvpn to exit in
     * ~1 ms before our mgmt reader thread can connect → mgmt-connect-failed.
     *
     * Safe to zero here because openvpn is not running between sessions.
     */
    memset(&siginfo_static, 0, sizeof(siginfo_static));
    return _uma_openvpn2_main_internal_(argc, argv);
}
