# Migrating from badvpn to hev-socks5-tunnel

This guide explains the steps needed to replace the badvpn tun2socks implementation with heiher/hev-socks5-tunnel in v2rayNG.

## What is hev-socks5-tunnel?

[hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) is a modern, high-performance tun2socks implementation that offers several advantages over badvpn:

- Better UDP support with fullcone NAT
- Native IPv6 dual-stack support
- Better performance and lower resource usage
- Active maintenance and development
- Better compatibility with modern Android systems

## Implementation Changes

The following changes were made to replace badvpn with hev-socks5-tunnel:

1. Updated the `.gitmodules` file to replace the badvpn submodule with hev-socks5-tunnel
2. Created a new `compile-hev-socks5-tunnel.sh` script to build the native library
3. Modified the `V2RayVpnService.kt` to:
   - Change the binary name from "libtun2socks.so" to "libhev-socks5-tunnel.so"
   - Generate a YAML configuration file instead of command-line arguments
   - Update the process execution and file descriptor handling
4. Updated the GitHub Actions workflow to use the new compilation script

## How to Test

1. Clone the repository with the new submodule:
   ```bash
   git clone --recursive https://github.com/your-username/v2rayNG
   ```

2. Build the native libraries (requires NDK):
   ```bash
   export NDK_HOME=/path/to/your/ndk
   bash compile-hev-socks5-tunnel.sh
   ```

3. Build the APK as usual:
   ```bash
   cd V2rayNG
   ./gradlew assembleDebug
   ```

4. Install and test all VPN features, particularly:
   - TCP connections
   - UDP functionality
   - IPv6 support (if enabled)
   - DNS resolution

## Known Issues

- The configuration format is completely different from badvpn, so any direct badvpn-specific customizations will need to be migrated to the new format.
- If you encounter connection issues, check the Android logs for any errors from "libhev-socks5-tunnel.so".

## Performance Comparison

hev-socks5-tunnel generally offers better performance than badvpn, especially:

- Lower CPU usage
- Better memory efficiency
- Improved throughput for both TCP and UDP connections
- Better handling of multiple concurrent connections

## References

- [hev-socks5-tunnel GitHub repository](https://github.com/heiher/hev-socks5-tunnel)
- [SocksTun - A simple Android VPN based on hev-socks5-tunnel](https://github.com/heiher/sockstun)
- [badvpn repository](https://github.com/ambrop72/badvpn) 