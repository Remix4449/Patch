package fr.regie.patch;

import android.content.ContentResolver;
import android.net.Uri;
import android.util.Xml;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Lecture d'un fichier GDTF (archive zip contenant description.xml).
 *
 * L'empreinte d'un mode est le plus grand décalage déclaré par ses canaux :
 * c'est la définition du format, et donc le nombre de canaux à réserver.
 */
public class Gdtf {

    public static String lire(ContentResolver cr, Uri uri) {
        InputStream in = null;
        try {
            in = cr.openInputStream(uri);
            if (in == null) return erreur("fichier illisible");
            ZipInputStream z = new ZipInputStream(in);
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                String nom = e.getName();
                if (nom != null && nom.equalsIgnoreCase("description.xml")) return analyser(z);
            }
            return erreur("description.xml introuvable — est-ce bien un fichier GDTF ?");
        } catch (Exception ex) {
            return erreur("lecture impossible");
        } finally {
            try { if (in != null) in.close(); } catch (Exception ignore) { }
        }
    }

    private static String analyser(InputStream in) throws Exception {
        XmlPullParser p = Xml.newPullParser();
        p.setInput(in, null);

        String nom = "", fabricant = "";
        String modeCourant = null;
        Map<String, Integer> modes = new LinkedHashMap<String, Integer>();
        // Pour allumer un appareil : ses canaux d'intensité et la valeur par
        // défaut de chaque canal (pan au centre, shutter ouvert…), par mode.
        Map<String, Map<Integer, Integer>> defauts = new LinkedHashMap<String, Map<Integer, Integer>>();
        Map<String, JSONArray> dims = new LinkedHashMap<String, JSONArray>();
        int[] decal = null;             // décalages du canal en cours, octet fort d'abord
        String defCanal = null, fnInit = null, defInit = null, defPremier = null;
        boolean dimmer = false;

        int ev = p.getEventType();
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                String t = p.getName();
                if ("FixtureType".equals(t)) {
                    if (nom.isEmpty()) {
                        nom = val(attr(p, "LongName"), attr(p, "Name"));
                        fabricant = val(attr(p, "Manufacturer"), "");
                    }
                } else if ("DMXMode".equals(t)) {
                    modeCourant = attr(p, "Name");
                    if (modeCourant != null && !modes.containsKey(modeCourant)) {
                        modes.put(modeCourant, 0);
                        defauts.put(modeCourant, new LinkedHashMap<Integer, Integer>());
                        dims.put(modeCourant, new JSONArray());
                    }
                } else if ("DMXChannel".equals(t) && modeCourant != null
                        && modes.containsKey(modeCourant)) {
                    int m = plusGrandDecalage(attr(p, "Offset"));
                    if (m > modes.get(modeCourant)) modes.put(modeCourant, m);
                    decal = decalages(attr(p, "Offset"));
                    defCanal = attr(p, "Default");
                    String ini = attr(p, "InitialFunction");
                    fnInit = ini == null ? null : ini.substring(ini.lastIndexOf('.') + 1);
                    defInit = null; defPremier = null; dimmer = false;
                } else if ("LogicalChannel".equals(t) && decal != null) {
                    if ("Dimmer".equals(attr(p, "Attribute"))) dimmer = true;
                } else if ("ChannelFunction".equals(t) && decal != null) {
                    String d = attr(p, "Default");
                    if (defPremier == null) defPremier = d;
                    if (fnInit != null && fnInit.equals(attr(p, "Name"))) defInit = d;
                }
            } else if (ev == XmlPullParser.END_TAG && "DMXChannel".equals(p.getName())) {
                if (decal != null && decal.length > 0 && modeCourant != null) {
                    if (dimmer) {
                        for (int o : decal) dims.get(modeCourant).put(o);
                    } else {
                        String d = defCanal != null ? defCanal : defInit != null ? defInit : defPremier;
                        long v = valeur(d, decal.length);
                        for (int i = 0; i < decal.length; i++) {
                            int b = (int) ((v >> (8 * (decal.length - 1 - i))) & 255);
                            if (b > 0) defauts.get(modeCourant).put(decal[i], b);
                        }
                    }
                }
                decal = null;
            } else if (ev == XmlPullParser.END_TAG && "DMXMode".equals(p.getName())) {
                modeCourant = null;
            }
            ev = p.next();
        }

        JSONArray a = new JSONArray();
        for (Map.Entry<String, Integer> e : modes.entrySet()) {
            if (e.getValue() <= 0) continue;
            JSONObject j = new JSONObject();
            j.put("nom", e.getKey());
            j.put("ch", e.getValue());
            JSONArray dim = dims.get(e.getKey());
            if (dim.length() > 0) {
                j.put("dim", dim);
                JSONObject def = new JSONObject();
                for (Map.Entry<Integer, Integer> x : defauts.get(e.getKey()).entrySet())
                    def.put(String.valueOf(x.getKey()), x.getValue());
                j.put("def", def);
            }
            a.put(j);
        }
        JSONObject o = new JSONObject();
        o.put("pret", true);
        o.put("nom", (fabricant.isEmpty() ? "" : fabricant + " ") + nom);
        o.put("modes", a);
        return o.toString();
    }

    /** « 1,2 » vaut deux canaux à partir du décalage 1 : on garde le plus grand. */
    private static int plusGrandDecalage(String offset) {
        if (offset == null) return 0;
        int max = 0;
        for (String part : offset.split(",")) {
            try {
                int v = Integer.parseInt(part.trim());
                if (v > max) max = v;
            } catch (NumberFormatException ignore) { }
        }
        return max;
    }

    private static int[] decalages(String offset) {
        if (offset == null || offset.trim().isEmpty()) return null;
        String[] t = offset.split(",");
        int[] r = new int[t.length];
        try { for (int i = 0; i < t.length; i++) r[i] = Integer.parseInt(t[i].trim()); }
        catch (NumberFormatException e) { return null; }
        return r;
    }

    /** « 32768/2 » : une valeur écrite sur 2 octets, ramenée à la résolution du canal. */
    private static long valeur(String dmx, int octets) {
        if (dmx == null) return 0;
        try {
            String[] t = dmx.trim().split("/");
            long v = Long.parseLong(t[0].trim());
            int n = t.length > 1 ? Integer.parseInt(t[1].trim()) : 1;
            int dec = 8 * (octets - n);
            return dec >= 0 ? v << dec : v >> -dec;
        } catch (Exception e) { return 0; }
    }

    private static String attr(XmlPullParser p, String nom) {
        return p.getAttributeValue(null, nom);
    }

    private static String val(String a, String b) {
        if (a != null && !a.trim().isEmpty()) return a.trim();
        return b == null ? "" : b.trim();
    }

    private static String erreur(String message) {
        try {
            JSONObject o = new JSONObject();
            o.put("erreur", message);
            return o.toString();
        } catch (Exception e) { return "{\"erreur\":\"inconnue\"}"; }
    }
}
