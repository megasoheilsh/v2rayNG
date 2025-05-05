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
import java.io.FileDescriptor
import java.lang.ref.SoftReference
import java.util.concurrent.TimeUnit

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
        stopV2Ray(true)
    }

//    override fun onLowMemory() {
//        stopV2Ray()
//        super.onLowMemory()
//    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        Log.i(AppConfig.TAG, "Destroying VPN service")
        stopTun2socks()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        restartCounter = 0  // Reset the counter on service start
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
     */
    private fun setup() {
        val prepare = prepare(this)
        if (prepare != null) {
            return
        }

        val packageName = packageName
        Log.i(AppConfig.TAG, "Setting up VPN service for $packageName")
        val confs = MmkvManager.decodeServerConfig()
        if (confs.isEmpty()) {
            Log.e(AppConfig.TAG, "No server configurations found")
            shutdown()
            return
        }

        try {
            val safe = true
            val ips = HashSet<String>()
            confs.forEach { conf ->
                val serverConfig = conf.second
                serverConfig.getProxyOutboundBean()?.let { outbound ->
                    val serverAddress = outbound.getServerAddress()
                    if (!serverAddress.isNullOrEmpty()) {
                        ips.add(serverAddress)
                    }
                }
            }

            val selectedConfig = MmkvManager.decodeServerConfig(SettingsManager.INSTANCE.currentVmessServerId)
            if (selectedConfig?.second == null) {
                Log.e(AppConfig.TAG, "Error: Selected server is null")
                throw Exception("Selected server is null")
            }
            val destService = selectedConfig.second.getProxyOutboundBean()?.settings?.vnext?.get(0)?.address
            if (destService.isNullOrEmpty()) {
                Log.e(AppConfig.TAG, "Error: Failed to get destination address")
                throw Exception("Failed to get destination address")
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // connectivity.requestNetwork(defaultNetworkRequest, defaultNetworkCallback)
            }

            startVpn(packageName, ips)
            
            // Initialize tun2socks
            if (::mInterface.isInitialized) {
                isRunning = true
                restartCounter = 0  // Reset restart counter
                runTun2socks()
            } else {
                Log.e(AppConfig.TAG, "Failed to create VPN interface")
                throw Exception("Failed to create VPN interface")
            }

        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to setup VPN", e)
            stopV2Ray(true)
            shutdown()
        }
    }

    /**
     * Starts the VPN service.
     * @param packageName The package name of the application.
     * @param ips The list of IPs to bypass.
     */
    private fun startVpn(packageName: String, ips: Set<String>) {
        Log.i(AppConfig.TAG, "Starting VPN service for $packageName")
        
        // Setup the VPN interface
        if (!setupVpnService()) {
            Log.e(AppConfig.TAG, "Failed to set up VPN interface")
            throw Exception("Failed to set up VPN interface")
        }
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
            stopV2Ray(true)
        }
        return false
    }

    /**
     * Utility method to get the actual integer file descriptor from a FileDescriptor object
     */
    private fun getIntFileDescriptor(fd: FileDescriptor): Int {
        return try {
            // Try to access the descriptor field using reflection
            val field = FileDescriptor::class.java.getDeclaredField("descriptor")
            field.isAccessible = true
            field.getInt(fd)
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to get file descriptor", e)
            -1 // Return -1 on failure
        }
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
        
        // Create a well-formatted YAML config
        // Make sure indentation and formatting are precise
        val configBuilder = StringBuilder()
        configBuilder.append("tunnel:\n")
        configBuilder.append("  mtu: $VPN_MTU\n")
        configBuilder.append("  ipv4: $PRIVATE_VLAN4_ROUTER\n")
        
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_PREFER_IPV6)) {
            configBuilder.append("  ipv6: '$PRIVATE_VLAN6_ROUTER'\n")
        }
        
        configBuilder.append("socks5:\n")
        configBuilder.append("  address: 127.0.0.1\n")
        configBuilder.append("  port: $socksPort\n")
        configBuilder.append("  udp: 'udp'\n")
        
        configBuilder.append("misc:\n")
        configBuilder.append("  log-level: 'debug'\n")
        // Use very minimal sizes to avoid crashes on older devices
        configBuilder.append("  tcp-buffer-size: 2048\n")
        configBuilder.append("  task-stack-size: 8192\n")
        
        Log.i(AppConfig.TAG, "Writing config to: ${configFile.absolutePath}")
        configFile.writeText(configBuilder.toString())
        Log.i(AppConfig.TAG, "Config: ${configBuilder.toString()}")
        
        // Get the file descriptor for the tun interface
        val tunFd = getIntFileDescriptor(mInterface.fileDescriptor)
        if (tunFd == -1) {
            Log.e(AppConfig.TAG, "Failed to get valid file descriptor")
            return
        }
        Log.i(AppConfig.TAG, "Using tun interface with fd: $tunFd")

        // Command to run hev-socks5-tunnel
        val cmd = arrayListOf(
            File(applicationContext.applicationInfo.nativeLibraryDir, TUN2SOCKS).absolutePath,
            configFile.absolutePath,
            tunFd.toString()  // Pass the actual int file descriptor
        )
        
        Log.i(AppConfig.TAG, "Command: ${cmd.joinToString(" ")}")

        // Track consecutive failures to prevent rapid restarts
        if (restartCounter > 5) {
            Log.e(AppConfig.TAG, "Too many consecutive restarts, giving up")
            return
        }

        try {
            val proBuilder = ProcessBuilder(cmd)
            proBuilder.redirectErrorStream(true)
            process = proBuilder
                .directory(applicationContext.filesDir)
                .start()
            
            // Debug: read process output to see what's happening
            Thread {
                try {
                    process.inputStream.bufferedReader().use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            Log.i(AppConfig.TAG, "$TUN2SOCKS output: $line")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(AppConfig.TAG, "Error reading process output", e)
                }
            }.start()
            
            Thread {
                Log.i(AppConfig.TAG, "$TUN2SOCKS check")
                val exitCode = process.waitFor()
                
                // Analyze exit code
                val exitMessage = when(exitCode) {
                    132 -> "SIGILL (Illegal instruction) - This may indicate the binary is incompatible with your device CPU"
                    139 -> "SIGSEGV (Segmentation fault) - Memory access violation"
                    134 -> "SIGABRT (Aborted) - The process was aborted"
                    else -> "Unknown error code: $exitCode"
                }
                
                Log.e(AppConfig.TAG, "$TUN2SOCKS exited with code: $exitCode ($exitMessage)")
                
                if (isRunning) {
                    // Only restart if the service is still running
                    restartCounter++
                    
                    // Add increasing delay before restart to prevent CPU overload
                    val delay = 1000L * restartCounter
                    Log.i(AppConfig.TAG, "Waiting $delay ms before restart")
                    Thread.sleep(delay)
                    
                    Log.i(AppConfig.TAG, "$TUN2SOCKS restart (attempt $restartCounter)")
                    runTun2socks()
                }
            }.start()
            
            Log.i(AppConfig.TAG, "$TUN2SOCKS process info: ${process.toString()}")
            
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to start $TUN2SOCKS process", e)
            restartCounter++
        }
    }

    // Counter to track consecutive failures
    private var restartCounter = 0

    /**
     * This method is no longer needed with hev-socks5-tunnel as we pass the file descriptor directly
     */
    private fun sendFd() {
        // Not needed for hev-socks5-tunnel
        Log.i(AppConfig.TAG, "sendFd() is not needed for hev-socks5-tunnel")
    }

    /**
     * Stops the V2Ray service.
     * @param isForced Whether to force stop the service.
     */
    override fun stopV2Ray(isForced: Boolean) {
        isRunning = false
        
        // Stop the tun2socks process first
        stopTun2socks()
        
        // Then continue with other cleanup
        if (::mInterface.isInitialized) {
            try {
                mInterface.close()
            } catch (ignored: Exception) {
            }
        }

        V2RayServiceManager.stopV2Ray()
        stopSelf()

        val configFiles: Array<String> = arrayOf("v2ray_os.json", "v2ray.json", "config.json")
        for (configFile in configFiles) {
            val cacheFile = File(packageCodePath, "assets/" + configFile)
            if (cacheFile.exists()) {
                cacheFile.delete()
            }
        }
    }

    private fun stopTun2socks() {
        isRunning = false
        try {
            if (::process.isInitialized) {
                Log.i(AppConfig.TAG, "Stopping $TUN2SOCKS process")
                try {
                    // Try to cleanly terminate the process first
                    process.destroy()
                    // Wait briefly for it to terminate
                    val terminated = process.waitFor(500, TimeUnit.MILLISECONDS)
                    if (!terminated) {
                        // Force kill if it didn't terminate gracefully
                        Log.w(AppConfig.TAG, "Process didn't terminate gracefully, forcing kill")
                        process.destroyForcibly()
                    }
                } catch (e: Exception) {
                    Log.e(AppConfig.TAG, "Error stopping process", e)
                }
            }
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to stop $TUN2SOCKS process", e)
        }
    }

    /**
     * Shuts down the VPN service.
     */
    private fun shutdown() {
        Log.i(AppConfig.TAG, "Shutting down VPN service")
        stopV2Ray(true)
        stopSelf()
    }
}
