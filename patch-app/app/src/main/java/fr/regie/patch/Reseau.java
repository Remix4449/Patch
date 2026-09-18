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

    /** Prévenue à chaque fois que l'adresse locale change, ou arrive enfin. */
    public interface Ecoute { void adresse(String ip, String diffusion); }

    private final Context ctx;
    private WifiManager.MulticastLock verrou;
    private ConnectivityManager.NetworkCallback rappel;
    private volatile Network wifi;
    private volatile Ecoute ecoute;

    public volatile String ip = "";
    public volatile String masque = "";
    public volatile String base = "";
    public volatile String diffusion = "255.255.255.255";
    public volatile int prefixe = 24;

    public Reseau(Context c) {
        ctx = c.getApplicationContext();
        prendreVerrou();
        suivreWifi();
    }

    /**
     * S'abonne aux changements d'adresse. Rappelée tout de suite si l'adresse
     * est déjà connue, pour qu'un abonné tardif ne rate pas le Wi-Fi.
     */
    public void surAdresse(Ecoute e) {
        ecoute = e;
        if (e != null && pret()) e.adresse(ip, diffusion);
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
                    lireAdresse(cm.getLinkProperties(n));
                }
                /*
                 * onAvailable arrive avant que le lien porte son adresse : le DHCP
                 * n'a pas fini. C'est ici, et seulement ici, que l'adresse est sûre,
                 * et c'est aussi ici qu'on apprend qu'elle a changé.
                 */
                @Override public void onLinkPropertiesChanged(Network n, LinkProperties lp) {
                    wifi = n;
                    lireAdresse(lp);
                }
                @Override public void onLost(Network n) {
                    wifi = null; ip = ""; base = "";
                    if (android.os.Build.VERSION.SDK_INT >= 23) cm.bindProcessToNetwork(null);
                }
            };
            cm.registerNetworkCallback(req, rappel);
        } catch (Exception ignore) { }
    }

    private void lireAdresse(LinkProperties lp) {
        try {
            if (lp == null) return;
            for (LinkAddress la : lp.getLinkAddresses()) {
                InetAddress a = la.getAddress();
                if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                    String avant = ip;
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
                    if (!ip.equals(avant)) {
                        Ecoute e = ecoute;
                        if (e != null) try { e.adresse(ip, diffusion); } catch (Exception ignore) { }
                    }
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
