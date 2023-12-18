package org.blissos.server.ethernet;

import static java.net.InetAddress.getByAddress;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.EthernetManager;
import android.net.InetAddresses;
import android.net.INetd;
import android.net.IpConfiguration;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.RouteInfo;
import android.net.StaticIpConfiguration;
import android.os.IBinder;
import android.os.INetworkManagementService;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;
import android.net.util.NetdService;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.android.server.SystemService;
import com.android.net.module.util.NetUtils;
import com.android.net.module.util.NetdUtils;

import org.blissos.ethernet.BlissEthernetManager;
import org.blissos.ethernet.IBlissEthernet;
import org.blissos.ethernet.IBlissEthernetServiceListener;

public class BlissEthernetService extends SystemService {

    private static final String TAG = "BlissEthernetService";
    private static final InetAddress INADDR_ANY;

    static {
        try {
            INADDR_ANY = getByAddress(new byte[] {0, 0, 0, 0});
        } catch (UnknownHostException e) {
            throw new RuntimeException(e);
        }
    }

    private final Context mContext;
    private EthernetManager mEthernetManager;
    private ConnectivityManager mCM;
    private INetworkManagementService mNMService;
    private INetd mNetd;

    public BlissEthernetService(Context context) {
        super(context);
        mContext = context;
    }

    @Override
    public void onStart() {
        mEthernetManager = (EthernetManager) mContext.getSystemService(Context.ETHERNET_SERVICE);
        mCM = (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);

        IBinder b = ServiceManager.getService(Context.NETWORKMANAGEMENT_SERVICE);
        mNMService = INetworkManagementService.Stub.asInterface(b);
        mNetd = Objects.requireNonNull(NetdService.getInstance(), "could not get netd instance");

        publishBinderService(BlissEthernetManager.SERVICE_NAME, mService);
    }

    private LinkProperties getInterfaceLinkProperties(String iface) {
        Network[] networks = mCM.getAllNetworks();
        for (Network network: networks) {
            if (mCM.getLinkProperties(network).getInterfaceName().equals(iface)) {
                return mCM.getLinkProperties(network);
            }
        }
        return null;
    }

    private final IBlissEthernet.Stub mService = new IBlissEthernet.Stub() {
        @Override
        public String[] getAvailableInterfaces() throws RemoteException {
            long token = clearCallingIdentity();

            String[] ret = mEthernetManager.getAvailableInterfaces();

            restoreCallingIdentity(token);
            return ret;
        }

        @Override
        public boolean isAvailable(String iface) throws RemoteException {
            long token = clearCallingIdentity();

            boolean ret = mEthernetManager.isAvailable(iface);

            restoreCallingIdentity(token);
            return ret;
        }

        @Override
        public void setListener(IBlissEthernetServiceListener listener) throws RemoteException {
            long token = clearCallingIdentity();

            mEthernetManager.addListener(new EthernetManager.Listener() {
                @Override
                public void onAvailabilityChanged(String iface, boolean isAvailable) {
                    try {
                        listener.onAvailabilityChanged(iface, isAvailable);
                    } catch (RemoteException e) {
                        throw new RuntimeException(e);
                    }
                }
            });
            restoreCallingIdentity(token);
        }

        @Override
        public void setInterfaceUp(String iface) throws RemoteException {
            long token = clearCallingIdentity();

            NetdUtils.setInterfaceUp(mNetd, iface);
            restoreCallingIdentity(token);
        }

        @Override
        public void setInterfaceDown(String iface) throws RemoteException {
            long token = clearCallingIdentity();

            NetdUtils.setInterfaceDown(mNetd, iface);
            restoreCallingIdentity(token);
        }

        @Override
        public String getEthernetMacAddress(String ifname) throws RemoteException {
            long token = clearCallingIdentity();

            String ret = mNMService.getInterfaceConfig(ifname).getHardwareAddress();

            restoreCallingIdentity(token);
            return ret;
        }

        @Override
        public int getIpAssignment(String iface) throws RemoteException {
            long token = clearCallingIdentity();

            IpConfiguration.IpAssignment ip = mEthernetManager.getConfiguration(iface).getIpAssignment();
            int ret = -1;
            switch (ip) {
                case DHCP:
                    return 0;
                case STATIC:
                    return 1;
            }

            restoreCallingIdentity(token);
            return ret;
        }

        @Override
        public void setIpAssignment(String iface, int assignment) throws RemoteException {
            long token = clearCallingIdentity();

            IpConfiguration ipconfig = mEthernetManager.getConfiguration(iface);
            switch (assignment) {
                case 0:
                    ipconfig.setIpAssignment(IpConfiguration.IpAssignment.DHCP);
                    break;
                case 1:
                    ipconfig.setIpAssignment(IpConfiguration.IpAssignment.STATIC);
                    break;
                default:
                    ipconfig.setIpAssignment(IpConfiguration.IpAssignment.UNASSIGNED);
            }
            mEthernetManager.setConfiguration(iface, ipconfig);
            restoreCallingIdentity(token);
        }

        @Override
        public String getIpAddress(String iface) throws RemoteException {
            long token = clearCallingIdentity();

            IpConfiguration ipconfig = mEthernetManager.getConfiguration(iface);
            StaticIpConfiguration staticIpconfig = ipconfig.getStaticIpConfiguration();

            if (staticIpconfig == null || ipconfig.getIpAssignment() != IpConfiguration.IpAssignment.STATIC) {
                List<String> addressList = new ArrayList<>();
                LinkProperties lp = getInterfaceLinkProperties(iface);
                if (lp == null) {
                    restoreCallingIdentity(token);
                    return null;
                }
                for (LinkAddress address : lp.getLinkAddresses()) {
                    if (address.isIpv4())
                        addressList.add(address.getAddress().getHostAddress() + "/" + address.getPrefixLength());
                }
                return String.join(",", addressList);
            }

            if (staticIpconfig.getIpAddress() == null) {
                restoreCallingIdentity(token);
                return null;
            }

            String ret = staticIpconfig.getIpAddress().getAddress().getHostAddress() + "/" + ipconfig.getStaticIpConfiguration().getIpAddress().getPrefixLength();
            restoreCallingIdentity(token);
            return ret;
        }

        @Override
        public void setIpAddress(String iface, String ipAddress) throws RemoteException {
            long token = clearCallingIdentity();

            IpConfiguration ipconfig = mEthernetManager.getConfiguration(iface);
            StaticIpConfiguration staticIpconfig = ipconfig.getStaticIpConfiguration();
            final StaticIpConfiguration.Builder staticIpConfigBuilder =
                    new StaticIpConfiguration.Builder();

            if (staticIpconfig != null && ipconfig.getIpAssignment() == IpConfiguration.IpAssignment.STATIC) {
                staticIpConfigBuilder.setGateway(staticIpconfig.getGateway());
                staticIpConfigBuilder.setDnsServers(staticIpconfig.getDnsServers());
                staticIpConfigBuilder.setDomains(staticIpconfig.getDomains());
            } else {
                final LinkProperties lp = getInterfaceLinkProperties(iface);
                if (lp != null) {
                    staticIpConfigBuilder.setDnsServers(lp.getDnsServers());
                    final RouteInfo ri = (lp != null)
                            ? NetUtils.selectBestRoute(lp.getAllRoutes(), INADDR_ANY)
                            : null;
                    if (ri != null)
                        staticIpConfigBuilder.setGateway(ri.getGateway());
                }
            }

            try {
                staticIpConfigBuilder.setIpAddress(new LinkAddress(ipAddress));
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "setIpAddress: " + e);
                restoreCallingIdentity(token);
                return;
            }

            ipconfig.setStaticIpConfiguration(staticIpConfigBuilder.build());
            ipconfig.setIpAssignment(IpConfiguration.IpAssignment.STATIC);
            mEthernetManager.setConfiguration(iface, ipconfig);
            restoreCallingIdentity(token);
        }

        @Override
        public String getGateway(String iface) throws RemoteException {
            long token = clearCallingIdentity();

            IpConfiguration ipconfig = mEthernetManager.getConfiguration(iface);
            StaticIpConfiguration staticIpconfig = ipconfig.getStaticIpConfiguration();

            if (staticIpconfig == null || ipconfig.getIpAssignment() != IpConfiguration.IpAssignment.STATIC) {
                final LinkProperties lp = getInterfaceLinkProperties(iface);
                final RouteInfo ri = (lp != null)
                        ? NetUtils.selectBestRoute(lp.getAllRoutes(), INADDR_ANY)
                        : null;

                restoreCallingIdentity(token);
                return (ri != null)
                        ? ri.getGateway().getHostAddress()
                        : null;
            }

            if (staticIpconfig.getGateway() == null) {
                restoreCallingIdentity(token);
                return null;
            }

            restoreCallingIdentity(token);

            String ret = staticIpconfig.getGateway().getHostAddress();
            return ret;
        }

        @Override
        public void setGateway(String iface, String gateway) throws RemoteException {
            long token = clearCallingIdentity();

            IpConfiguration ipconfig = mEthernetManager.getConfiguration(iface);
            StaticIpConfiguration staticIpconfig = ipconfig.getStaticIpConfiguration();
            final StaticIpConfiguration.Builder staticIpConfigBuilder =
                    new StaticIpConfiguration.Builder();
            if (staticIpconfig != null && ipconfig.getIpAssignment() == IpConfiguration.IpAssignment.STATIC) {
                staticIpConfigBuilder.setIpAddress(staticIpconfig.getIpAddress());
                staticIpConfigBuilder.setDnsServers(staticIpconfig.getDnsServers());
                staticIpConfigBuilder.setDomains(staticIpconfig.getDomains());
            } else {
                final LinkProperties lp = getInterfaceLinkProperties(iface);
                if (lp != null) {
                    staticIpConfigBuilder.setIpAddress(lp.getLinkAddresses().get(0));
                    staticIpConfigBuilder.setDnsServers(lp.getDnsServers());
                }
            }
            staticIpConfigBuilder.setGateway(InetAddresses.parseNumericAddress(gateway));

            ipconfig.setStaticIpConfiguration(staticIpConfigBuilder.build());
            ipconfig.setIpAssignment(IpConfiguration.IpAssignment.STATIC);
            mEthernetManager.setConfiguration(iface, ipconfig);
            restoreCallingIdentity(token);
        }

        @Override
        public String[] getDnses(String iface) throws RemoteException {
            long token = clearCallingIdentity();

            IpConfiguration ipconfig = mEthernetManager.getConfiguration(iface);
            StaticIpConfiguration staticIpconfig = ipconfig.getStaticIpConfiguration();

            if (staticIpconfig == null || ipconfig.getIpAssignment() != IpConfiguration.IpAssignment.STATIC) {
                final LinkProperties lp = getInterfaceLinkProperties(iface);
                if (lp == null) {
                    restoreCallingIdentity(token);
                    return new String[0];
                }
                List<InetAddress> dnses = lp.getDnsServers();
                String[] dnsesStr = new String[dnses.size()];
                for (InetAddress dns : dnses) {
                    dnsesStr[dnses.indexOf(dns)] = dns.getHostAddress();
                }
                restoreCallingIdentity(token);
                return dnsesStr;
            }

            if (staticIpconfig.getDnsServers() == null) {
                restoreCallingIdentity(token);
                return new String[0];
            }

            List<InetAddress> DnsServers = staticIpconfig.getDnsServers();
            String[] dnses = new String[DnsServers.size()];
            for (InetAddress dns : DnsServers) {
                dnses[DnsServers.indexOf(dns)] = dns.getHostAddress();
            }
            restoreCallingIdentity(token);
            return dnses;
        }

        @Override
        public void setDnses(String iface, String[] dnses) throws RemoteException {
            long token = clearCallingIdentity();

            IpConfiguration ipconfig = mEthernetManager.getConfiguration(iface);
            StaticIpConfiguration staticIpconfig = ipconfig.getStaticIpConfiguration();
            final StaticIpConfiguration.Builder staticIpConfigBuilder =
                    new StaticIpConfiguration.Builder();
            if (staticIpconfig != null && ipconfig.getIpAssignment() == IpConfiguration.IpAssignment.STATIC) {
                staticIpConfigBuilder.setIpAddress(staticIpconfig.getIpAddress());
                staticIpConfigBuilder.setGateway(staticIpconfig.getGateway());
                staticIpConfigBuilder.setDomains(staticIpconfig.getDomains());
            } else {
                final LinkProperties lp = getInterfaceLinkProperties(iface);
                if (lp != null) {
                    staticIpConfigBuilder.setIpAddress(lp.getLinkAddresses().get(0));
                    final RouteInfo ri = (lp != null)
                            ? NetUtils.selectBestRoute(lp.getAllRoutes(), INADDR_ANY)
                            : null;
                    if (ri != null)
                        staticIpConfigBuilder.setGateway(ri.getGateway());
                }
            }
            ArrayList<InetAddress> dnsAddresses = new ArrayList<>();
            for (String address: dnses) {
                dnsAddresses.add(InetAddresses.parseNumericAddress(address));
            }
            staticIpConfigBuilder.setDnsServers(dnsAddresses);

            ipconfig.setStaticIpConfiguration(staticIpConfigBuilder.build());
            ipconfig.setIpAssignment(IpConfiguration.IpAssignment.STATIC);
            mEthernetManager.setConfiguration(iface, ipconfig);

            restoreCallingIdentity(token);
        }
    };
}

