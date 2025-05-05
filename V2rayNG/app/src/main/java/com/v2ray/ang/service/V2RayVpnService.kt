package com.v2ray.ang.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.StrictMode
import android.util.Log
import androidx.annotation.RequiresApi
import com.v2ray.ang.AppConfig
import com.v2ray.ang.AppConfig.LOOPBACK
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.util.MyContextWrapper
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.lang.ref.SoftReference

class V2RayVpnService : VpnService(), ServiceControl {
    companion object {
        private const val VPN_MTU = 1500
        private const val PRIVATE_VLAN4_CLIENT = "10.10.14.1"
        private const val PRIVATE_VLAN4_ROUTER = "10.10.14.2"
        private const val PRIVATE_VLAN6_CLIENT = "fc00::10:10:14:1"
        private const val PRIVATE_VLAN6_ROUTER = "fc00::10:10:14:2"
        private const val TUN2SOCKS = "libhev-socks5-tunnel.so"

    }

    private lateinit var mInterface: ParcelFileDescriptor
    private var isRunning = false
    private lateinit var process: Process

    /**destroy
     * Unfortunately registerDefaultNetworkCallback is going to return our VPN interface: https://android.googlesource.com/platform/frameworks/base/+/dda156ab0c5d66ad82bdcf76cda07cbc0a9c8a2e
     *
     * This makes doing a requestNetwork with REQUEST necessary so that we don't get ALL possible networks that
     * satisfies default network capabilities but only THE default network. Unfortunately we need to have
     * android.permission.CHANGE_NETWORK_STATE to be able to call requestNetwork.
     *
     * Source: https://android.googlesource.com/platform/frameworks/base/+/2df4c7d/services/core/java/com/android/server/ConnectivityService.java#887
     */
    @delegate:RequiresApi(Build.VERSION_CODES.P)
    private val defaultNetworkRequest by lazy {
        NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            .build()
    }

    private val connectivity by lazy { getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager }

    @delegate:RequiresApi(Build.VERSION_CODES.P)
    private val defaultNetworkCallback by lazy {
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                setUnderlyingNetworks(arrayOf(network))
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                // it's a good idea to refresh capabilities
                setUnderlyingNetworks(arrayOf(network))
            }

            override fun onLost(network: Network) {
                setUnderlyingNetworks(null)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val policy = StrictMode.ThreadPolicy.Builder().permitAll().build()
        StrictMode.setThreadPolicy(policy)
        V2RayServiceManager.serviceControl = SoftReference(this)
    }

    override fun onRevoke() {
        stopV2Ray()
    }

//    override fun onLowMemory() {
//        stopV2Ray()
//        super.onLowMemory()
//    }

    override fun onDestroy() {
        super.onDestroy()
        NotificationService.cancelNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (V2RayServiceManager.startCoreLoop()) {
            startService()
        }
        return START_STICKY
        //return super.onStartCommand(intent, flags, startId)
    }

    override fun getService(): Service {
        return this
    }

    override fun startService() {
        setup()
    }

    override fun stopService() {
        stopV2Ray(true)
    }

    override fun vpnProtect(socket: Int): Boolean {
        return protect(socket)
    }

    @RequiresApi(Build.VERSION_CODES.N)
    override fun attachBaseContext(newBase: Context?) {
        val context = newBase?.let {
            MyContextWrapper.wrap(newBase, SettingsManager.getLocale())
        }
        super.attachBaseContext(context)
    }

    /**
     * Sets up the VPN service.
     * Prepares the VPN and configures it if preparation is successful.
     */
    private fun setup() {
        val prepare = prepare(this)
        if (prepare != null) {
            return
        }

        if (setupVpnService() != true) {
            return
        }

        runTun2socks()
    }

    /**
     * Configures the VPN service.
     * @return True if the VPN service was configured successfully, false otherwise.
     */
    private fun setupVpnService(): Boolean {
        // If the old interface has exactly the same parameters, use it!
        // Configure a builder while parsing the parameters.
        val builder = Builder()
        //val enableLocalDns = defaultDPreference.getPrefBoolean(AppConfig.PREF_LOCAL_DNS_ENABLED, false)

        builder.setMtu(VPN_MTU)
        builder.addAddress(PRIVATE_VLAN4_CLIENT, 30)
        //builder.addDnsServer(PRIVATE_VLAN4_ROUTER)
        val bypassLan = SettingsManager.routingRulesetsBypassLan()
        if (bypassLan) {
            AppConfig.BYPASS_PRIVATE_IP_LIST.forEach {
                val addr = it.split('/')
                builder.addRoute(addr[0], addr[1].toInt())
            }
        } else {
            builder.addRoute("0.0.0.0", 0)
        }

        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_PREFER_IPV6) == true) {
            builder.addAddress(PRIVATE_VLAN6_CLIENT, 126)
            if (bypassLan) {
                builder.addRoute("2000::", 3) //currently only 1/8 of total ipV6 is in use
            } else {
                builder.addRoute("::", 0)
            }
        }

//        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_LOCAL_DNS_ENABLED) == true) {
//            builder.addDnsServer(PRIVATE_VLAN4_ROUTER)
//        } else {
        SettingsManager.getVpnDnsServers()
            .forEach {
                if (Utils.isPureIpAddress(it)) {
                    builder.addDnsServer(it)
                }
            }
//        }

        builder.setSession(V2RayServiceManager.getRunningServerName())

        val selfPackageName = BuildConfig.APPLICATION_ID
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_PER_APP_PROXY)) {
            val apps = MmkvManager.decodeSettingsStringSet(AppConfig.PREF_PER_APP_PROXY_SET)
            val bypassApps = MmkvManager.decodeSettingsBool(AppConfig.PREF_BYPASS_APPS)
            //process self package
            if (bypassApps) apps?.add(selfPackageName) else apps?.remove(selfPackageName)
            apps?.forEach {
                try {
                    if (bypassApps)
                        builder.addDisallowedApplication(it)
                    else
                        builder.addAllowedApplication(it)
                } catch (e: PackageManager.NameNotFoundException) {
                    Log.e(AppConfig.TAG, "Failed to configure app in VPN: ${e.localizedMessage}", e)
                }
            }
        } else {
            builder.addDisallowedApplication(selfPackageName)
        }

        // Close the old interface since the parameters have been changed.
        try {
            mInterface.close()
        } catch (ignored: Exception) {
            // ignored
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                connectivity.requestNetwork(defaultNetworkRequest, defaultNetworkCallback)
            } catch (e: Exception) {
                Log.e(AppConfig.TAG, "Failed to request default network", e)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
            if (MmkvManager.decodeSettingsBool(AppConfig.PREF_APPEND_HTTP_PROXY)) {
                builder.setHttpProxy(ProxyInfo.buildDirectProxy(LOOPBACK, SettingsManager.getHttpPort()))
            }
        }

        // Create a new interface using the builder and save the parameters.
        try {
            mInterface = builder.establish()!!
            isRunning = true
            return true
        } catch (e: Exception) {
            // non-nullable lateinit var
            Log.e(AppConfig.TAG, "Failed to establish VPN interface", e)
            stopV2Ray()
        }
        return false
    }

    /**
     * Runs the tun2socks process.
     * Starts the hev-socks5-tunnel process with the appropriate parameters.
     */
    private fun runTun2socks() {
        Log.i(AppConfig.TAG, "Start run $TUN2SOCKS")
        val socksPort = SettingsManager.getSocksPort()
        
        // Create a YAML config string for hev-socks5-tunnel
        val configDir = applicationContext.filesDir
        val configFile = File(configDir, "hev-socks5-tunnel.yml")
        
        val configBuilder = StringBuilder()
        configBuilder.append("tunnel:\n")
        configBuilder.append("  name: tun0\n")
        configBuilder.append("  mtu: $VPN_MTU\n")
        configBuilder.append("  ipv4: $PRIVATE_VLAN4_ROUTER\n")
        
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_PREFER_IPV6)) {
            configBuilder.append("  ipv6: '$PRIVATE_VLAN6_ROUTER'\n")
        }
        
        configBuilder.append("\nsocks5:\n")
        configBuilder.append("  port: $socksPort\n")
        configBuilder.append("  address: $LOOPBACK\n")
        configBuilder.append("  udp: 'udp'\n")
        
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_LOCAL_DNS_ENABLED)) {
            val localDnsPort = Utils.parseInt(MmkvManager.decodeSettingsString(AppConfig.PREF_LOCAL_DNS_PORT), AppConfig.PORT_LOCAL_DNS.toInt())
            configBuilder.append("\nmisc:\n")
            configBuilder.append("  log-level: 'info'\n")
        }
        
        configFile.writeText(configBuilder.toString())
        
        // Command to run hev-socks5-tunnel
        val cmd = arrayListOf(
            File(applicationContext.applicationInfo.nativeLibraryDir, TUN2SOCKS).absolutePath,
            configFile.absolutePath,
            "-1" // Pass -1 as the tun_fd to let the tunnel create its own interface
        )
        
        Log.i(AppConfig.TAG, "Config: ${configBuilder.toString()}")
        Log.i(AppConfig.TAG, "Command: ${cmd.toString()}")

        try {
            val proBuilder = ProcessBuilder(cmd)
            proBuilder.redirectErrorStream(true)
            process = proBuilder
                .directory(applicationContext.filesDir)
                .start()
            
            Thread {
                Log.i(AppConfig.TAG, "$TUN2SOCKS check")
                process.waitFor()
                Log.i(AppConfig.TAG, "$TUN2SOCKS exited")
                if (isRunning) {
                    Log.i(AppConfig.TAG, "$TUN2SOCKS restart")
                    runTun2socks()
                }
            }.start()
            
            Log.i(AppConfig.TAG, "$TUN2SOCKS process info: ${process.toString()}")
            
            // Pass the file descriptor to the tunnel directly at startup instead of through sendFd()
            val fd = mInterface.fileDescriptor
            process.outputStream.write(fd.toString().toByteArray())
            process.outputStream.flush()
            
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to start $TUN2SOCKS process", e)
        }
    }

    /**
     * This method is no longer needed with hev-socks5-tunnel as we pass the file descriptor directly
     * It's kept as a no-op for compatibility
     */
    private fun sendFd() {
        // Not needed for hev-socks5-tunnel
        Log.i(AppConfig.TAG, "sendFd() is not needed for hev-socks5-tunnel")
    }

    /**
     * Stops the V2Ray service.
     * @param isForced Whether to force stop the service.
     */
    private fun stopV2Ray(isForced: Boolean = true) {
//        val configName = defaultDPreference.getPrefString(PREF_CURR_CONFIG_GUID, "")
//        val emptyInfo = VpnNetworkInfo()
//        val info = loadVpnNetworkInfo(configName, emptyInfo)!! + (lastNetworkInfo ?: emptyInfo)
//        saveVpnNetworkInfo(configName, info)
        isRunning = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                connectivity.unregisterNetworkCallback(defaultNetworkCallback)
            } catch (ignored: Exception) {
                // ignored
            }
        }

        try {
            Log.i(AppConfig.TAG, "$TUN2SOCKS destroy")
            process.destroy()
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to destroy $TUN2SOCKS process", e)
        }

        V2RayServiceManager.stopCoreLoop()

        if (isForced) {
            //stopSelf has to be called ahead of mInterface.close(). otherwise v2ray core cannot be stooped
            //It's strage but true.
            //This can be verified by putting stopself() behind and call stopLoop and startLoop
            //in a row for several times. You will find that later created v2ray core report port in use
            //which means the first v2ray core somehow failed to stop and release the port.
            stopSelf()

            try {
                mInterface.close()
            } catch (e: Exception) {
                Log.e(AppConfig.TAG, "Failed to close VPN interface", e)
            }
        }
    }
}
