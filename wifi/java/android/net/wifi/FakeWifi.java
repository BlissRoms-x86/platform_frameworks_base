package android.net.wifi;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiSsid;
import android.net.wifi.SupplicantState;
import android.net.DhcpInfo;
import android.os.PatternMatcher;
import android.os.SystemProperties;
import android.util.Log;

import java.lang.Exception;
import java.lang.reflect.Field;
import java.net.NetworkInterface;
import java.net.InetAddress;
import java.util.*;
import java.util.regex.Pattern;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;

public final class FakeWifi {
    private static final String LOG_TAG = "FakeWifi";

    private FakeWifi() {}

    public static boolean isHackEnabled(@NonNull Context context) {
        String packageName = context.getOpPackageName();
        boolean enabled = Arrays.stream(SystemProperties.get("persist.bliss.fake_wifi").split(","))
            .map(x -> x.replace(".", "\\."))
            .map(x -> x.replace("*", ".*"))
            .map(x -> new PatternMatcher(x, PatternMatcher.PATTERN_SIMPLE_GLOB))
            .anyMatch(p -> p.match(packageName));

        if (enabled)
            Log.d(LOG_TAG, "Faking wifi for " + packageName);

        return enabled;
    }

    @NonNull
    public static NetworkInfo getFakeNetworkInfo()
    {
        NetworkInfo info = createNetworkInfo(ConnectivityManager.TYPE_WIFI, true);
        return info;
    }

    @NonNull
    public static NetworkInfo createNetworkInfo(final int type, final boolean connected)
    {
        NetworkInfo networkInfo = new NetworkInfo(type, 0, "WIFI", null);
        networkInfo.setDetailedState(NetworkInfo.DetailedState.CONNECTED, null, null);

        // networkInfo.setIsAvailable(true);
        try {
            Field isAvailable = networkInfo.getClass().getDeclaredField("mIsAvailable");
            isAvailable.setAccessible(true);
            isAvailable.setBoolean(networkInfo, true);
        } catch (Exception e) {
            e.printStackTrace();
        }

        return networkInfo;
    }

    @NonNull
    private static WifiSsid createWifiSsid()
    {
        return WifiSsid.createFromAsciiEncoded("FakeWifi");
    }

    @NonNull
    public static WifiInfo createWifiInfo()
    {
        IpInfo ip = getIpInfo();
        InetAddress addr = (ip != null ? ip.addr : null);

        WifiInfo info = new WifiInfo.Builder()
                .setNetworkId(1)
                .setBssid("66:55:44:33:22:11")
                .setRssi(200) // MAX_RSSI
                .build();

        info.setSupplicantState(SupplicantState.COMPLETED);
        info.setMacAddress("11:22:33:44:55:66");
        info.setInetAddress(addr);
        info.setLinkSpeed(65);  // Mbps
        info.setFrequency(5000); // MHz
        info.setSSID(createWifiSsid());
        return info;
    }

    public static class IpInfo
    {
        NetworkInterface intf;
        InetAddress addr;
        String ip;
        int ip_hex;
        int netmask_hex;
    }

    // get current ip and netmask
    @Nullable
    public static IpInfo getIpInfo()
    {
        try
        {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface intf : interfaces)
            {
                List<InetAddress> addrs = Collections.list(intf.getInetAddresses());
                for (InetAddress addr : addrs)
                {
                    if (!addr.isLoopbackAddress())
                    {
                        String sAddr = addr.getHostAddress().toUpperCase();
                        boolean isIPv4 = isIPv4Address(sAddr);
                        if (isIPv4)
                        {
                            IpInfo info = new IpInfo();
                            info.addr = addr;
                            info.intf = intf;
                            info.ip = sAddr;
                            info.ip_hex = InetAddress_to_hex(addr);
                            info.netmask_hex = netmask_to_hex(intf.getInterfaceAddresses().get(0).getNetworkPrefixLength());
                            return info;
                        }
                    }
                }
            }
        } catch (Exception ex) { } // for now eat exceptions
        return null;
    }


    public static boolean isIPv4Address(@NonNull String input) {
        Pattern IPV4_PATTERN = Pattern.compile("^(25[0-5]|2[0-4]\\d|[0-1]?\\d?\\d)(\\.(25[0-5]|2[0-4]\\d|[0-1]?\\d?\\d)){3}$");
        return IPV4_PATTERN.matcher(input).matches();
    }

    public static int netmask_to_hex(int netmask_slash)
    {
        int r = 0;
        int b = 1;
        for (int i = 0; i < netmask_slash;  i++, b = b << 1)
            r |= b;
        return r;
    }

    // for DhcpInfo
    private static int InetAddress_to_hex(InetAddress a)
    {
        int result = 0;
        byte b[] = a.getAddress();
        for (int i = 0; i < 4; i++)
            result |= (b[i] & 0xff) << (8 * i);
        return result;
    }

    @NonNull
    public static DhcpInfo createDhcpInfo()
    {
        DhcpInfo i = new DhcpInfo();
        IpInfo ip = getIpInfo();
        i.ipAddress = ip.ip_hex;
        i.netmask = ip.netmask_hex;
        i.dns1 = 0x04040404;
        i.dns2 = 0x08080808;
        // gateway, leaseDuration, serverAddress

        return i;
    }

    @Nullable
    public static NetworkInfo maybeOverwrite(@Nullable NetworkInfo network) {
        if (network == null || network.getType() != ConnectivityManager.TYPE_WIFI || !network.isConnected())
            network = getFakeNetworkInfo();
        return network;
    }
}
