package fr.regie.patch;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CalendarContract;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Lecture de l'agenda du téléphone, celui que partagent toutes les
 * applications d'agenda d'Android (Google Agenda, Today, Samsung…). On ne fait
 * que lire : les agendas disponibles, puis les rendez-vous d'une période dans
 * ceux qu'on a choisis.
 */
public class Agenda {

    /** Les agendas du téléphone : [{ id, nom, compte, couleur }]. */
    public static String agendas(ContentResolver cr) {
        JSONArray out = new JSONArray();
        Cursor c = null;
        try {
            c = cr.query(CalendarContract.Calendars.CONTENT_URI, new String[] {
                    CalendarContract.Calendars._ID,
                    CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                    CalendarContract.Calendars.ACCOUNT_NAME,
                    CalendarContract.Calendars.CALENDAR_COLOR,
                    CalendarContract.Calendars.VISIBLE }, null, null, null);
            while (c != null && c.moveToNext()) {
                out.put(new JSONObject()
                        .put("id", c.getLong(0))
                        .put("nom", c.isNull(1) ? "" : c.getString(1))
                        .put("compte", c.isNull(2) ? "" : c.getString(2))
                        .put("couleur", String.format(Locale.ROOT, "#%06X", 0xFFFFFF & c.getInt(3)))
                        .put("visible", c.getInt(4) != 0));
            }
        } catch (Exception e) {
            return erreur(e instanceof SecurityException ? "autorisation refusée" : "agenda illisible");
        } finally {
            if (c != null) c.close();
        }
        return out.toString();
    }

    /**
     * Les rendez-vous entre debut et fin (millisecondes) des agendas nommés par
     * leurs identifiants séparés par des virgules. Chaque occurrence d'un
     * rendez-vous répété vient séparément, avec sa propre date.
     */
    public static String lire(ContentResolver cr, String ids, long debut, long fin) {
        JSONArray out = new JSONArray();
        StringBuilder sel = new StringBuilder();
        for (String s : (ids == null ? "" : ids).split(",")) {
            s = s.trim();
            if (!s.matches("\\d+")) continue;
            sel.append(sel.length() == 0 ? "" : ",").append(s);
        }
        if (sel.length() == 0) return out.toString();
        Uri.Builder b = CalendarContract.Instances.CONTENT_URI.buildUpon();
        ContentUris.appendId(b, debut);
        ContentUris.appendId(b, fin);
        Cursor c = null;
        try {
            c = cr.query(b.build(), new String[] {
                    CalendarContract.Instances.EVENT_ID,
                    CalendarContract.Instances.BEGIN,
                    CalendarContract.Instances.END,
                    CalendarContract.Instances.TITLE,
                    CalendarContract.Instances.EVENT_LOCATION,
                    CalendarContract.Instances.DESCRIPTION,
                    CalendarContract.Instances.ALL_DAY,
                    CalendarContract.Instances.CALENDAR_ID,
                    CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
                    CalendarContract.Instances.STATUS },
                    CalendarContract.Instances.CALENDAR_ID + " IN (" + sel + ")", null,
                    CalendarContract.Instances.BEGIN + " ASC");
            while (c != null && c.moveToNext()) {
                if (!c.isNull(9) && c.getInt(9) == CalendarContract.Instances.STATUS_CANCELED) continue;
                boolean journee = c.getInt(6) != 0;
                long d = c.getLong(1), f = c.getLong(2);
                JSONObject o = new JSONObject()
                        .put("id", c.getLong(0))
                        .put("titre", c.isNull(3) ? "" : c.getString(3))
                        .put("lieu", c.isNull(4) ? "" : c.getString(4))
                        .put("note", c.isNull(5) ? "" : c.getString(5))
                        .put("agenda", c.getLong(7))
                        .put("nomAgenda", c.isNull(8) ? "" : c.getString(8))
                        .put("journee", journee);
                if (journee) {
                    // Les journées entières sont posées à minuit UTC, fin exclue.
                    o.put("debut", jour(d, true)).put("fin", jour(f - 1, true));
                } else {
                    o.put("debut", jour(d, false)).put("hdebut", heure(d))
                     .put("fin", jour(f, false)).put("hfin", heure(f));
                }
                out.put(o);
            }
        } catch (Exception e) {
            return erreur(e instanceof SecurityException ? "autorisation refusée" : "agenda illisible");
        } finally {
            if (c != null) c.close();
        }
        return out.toString();
    }

    private static String jour(long ms, boolean utc) {
        Calendar k = Calendar.getInstance(utc ? TimeZone.getTimeZone("UTC") : TimeZone.getDefault());
        k.setTimeInMillis(ms);
        return String.format(Locale.ROOT, "%04d-%02d-%02d", k.get(Calendar.YEAR), k.get(Calendar.MONTH) + 1,
                k.get(Calendar.DAY_OF_MONTH));
    }

    private static String heure(long ms) {
        Calendar k = Calendar.getInstance();
        k.setTimeInMillis(ms);
        return String.format(Locale.ROOT, "%02d:%02d", k.get(Calendar.HOUR_OF_DAY), k.get(Calendar.MINUTE));
    }

    private static String erreur(String m) {
        try { return new JSONObject().put("erreur", m).toString(); }
        catch (Exception e) { return "{\"erreur\":\"agenda\"}"; }
    }
}
