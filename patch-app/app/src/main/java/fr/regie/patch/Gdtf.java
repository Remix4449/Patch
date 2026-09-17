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
                    if (modeCourant != null && !modes.containsKey(modeCourant))
                        modes.put(modeCourant, 0);
                } else if ("DMXChannel".equals(t) && modeCourant != null
                        && modes.containsKey(modeCourant)) {
                    int m = plusGrandDecalage(attr(p, "Offset"));
                    if (m > modes.get(modeCourant)) modes.put(modeCourant, m);
                }
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
