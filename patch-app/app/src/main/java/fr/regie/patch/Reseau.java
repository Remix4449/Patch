package fr.regie.patch;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiManager;

import java.net.Inet4Address;
import java.net.InetAddress;

/**
 * Accroche le processus au Wi-Fi et garde le verrou multicast.
 *
 * Sans cela, Android route les paquets vers la 4G dès que le Wi-Fi n'a pas
 * d'accès internet — ce qui est exactement le cas d'un réseau de plateau.
 */
public class Reseau {

    private final Context ctx;
    private WifiManager.MulticastLock verrou;
    private ConnectivityManager.NetworkCallback rappel;
    private volatile Network wifi;

    public String ip = "";
    public String masque = "";
    public String base = "";
    public String diffusion = "255.255.255.255";
    public int prefixe = 24;

    public Reseau(Context c) {
        ctx = c.getApplicationContext();
        prendreVerrou();
        suivreWifi();
    }

    private void prendreVerrou() {
        try {
            WifiManager wm = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
            if (wm == null) return;
            verrou = wm.createMulticastLock("patch-regie");
            verrou.setReferenceCounted(false);
            verrou.acquire();
        } catch (Exception ignore) { }
    }

    private void suivreWifi() {
        try {
            final ConnectivityManager cm =
                    (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return;
            NetworkRequest req = new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .build();
            rappel = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network n) {
                    wifi = n;
                    if (android.os.Build.VERSION.SDK_INT >= 23) cm.bindProcessToNetwork(n);
                    lireAdresse(cm, n);
                }
                @Override public void onLost(Network n) {
                    wifi = null; ip = ""; base = "";
                    if (android.os.Build.VERSION.SDK_INT >= 23) cm.bindProcessToNetwork(null);
                }
            };
            cm.registerNetworkCallback(req, rappel);
        } catch (Exception ignore) { }
    }

    private void lireAdresse(ConnectivityManager cm, Network n) {
        try {
            LinkProperties lp = cm.getLinkProperties(n);
            if (lp == null) return;
            for (LinkAddress la : lp.getLinkAddresses()) {
                InetAddress a = la.getAddress();
                if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                    ip = a.getHostAddress();
                    prefixe = la.getPrefixLength();
                    int m = prefixe >= 32 ? -1 : ~((1 << (32 - prefixe)) - 1);
                    masque = ((m >> 24) & 255) + "." + ((m >> 16) & 255) + "."
                           + ((m >> 8) & 255) + "." + (m & 255);
                    byte[] o = a.getAddress();
                    int adr = ((o[0] & 255) << 24) | ((o[1] & 255) << 16)
                            | ((o[2] & 255) << 8) | (o[3] & 255);
                    int bc = adr | ~m;
                    diffusion = ((bc >> 24) & 255) + "." + ((bc >> 16) & 255) + "."
                              + ((bc >> 8) & 255) + "." + (bc & 255);
                    base = ip.substring(0, ip.lastIndexOf('.'));
                    return;
                }
            }
        } catch (Exception ignore) { }
    }

    public boolean pret() { return !ip.isEmpty(); }

    public void liberer() {
        try { if (verrou != null && verrou.isHeld()) verrou.release(); } catch (Exception ignore) { }
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null && rappel != null) cm.unregisterNetworkCallback(rappel);
        } catch (Exception ignore) { }
    }
}
