package com.gathin.tvhome;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;

import java.util.Random;

/**
 * A still landscape behind Home (config.xml or a theme's "scene"): rolling hills, a small house between pines,
 * and a sky that follows the time of day and the weather.
 *
 * "landscape": dawn, day, sunset and night from the real sunrise and sunset (Sun, at the weather location);
 * the sun travels across the sky by day, the moon and stars come out at night, and the house's windows light up
 * after dusk. "night": the night picture all day.
 *
 * The weather (a WMO code from WeatherLine) adds clouds, rain, drizzle, snow, fog or a storm sky; overcast skies
 * hide the sun, moon and stars. Everything is drawn once into SkyView's small texture and stretched over the
 * screen: redrawn at most once a minute, never animated, no bitmap assets.
 */
final class LandscapeScene {

    private LandscapeScene() {
    }

    /** Weather, simplified to what the picture can show. */
    enum Kind { CLEAR, PARTLY, CLOUDY, FOG, DRIZZLE, RAIN, STORM, SNOW }

    static Kind kindOf(int wmo) {
        if (wmo < 0) return Kind.CLEAR;
        if (wmo <= 1) return Kind.CLEAR;
        if (wmo == 2) return Kind.PARTLY;
        if (wmo == 3) return Kind.CLOUDY;
        if (wmo == 45 || wmo == 48) return Kind.FOG;
        if (wmo >= 51 && wmo <= 57) return Kind.DRIZZLE;
        if ((wmo >= 61 && wmo <= 67) || (wmo >= 80 && wmo <= 82)) return Kind.RAIN;
        if ((wmo >= 71 && wmo <= 77) || wmo == 85 || wmo == 86) return Kind.SNOW;
        if (wmo >= 95) return Kind.STORM;
        return Kind.CLEAR;
    }

    // Night colours, from the reference picture: navy rather than black, so the hills read against it.
    private static final int NIGHT_TOP = 0xFF070E24;
    private static final int NIGHT_MID = 0xFF111D40;
    private static final int NIGHT_BOTTOM = 0xFF1C2B57;

    /**
     * @param minute   minute of the day, 0 to 1439
     * @param sunrise  minute of sunrise, or -1 for the reference day
     * @param sunset   minute of sunset, or -1
     * @param alwaysNight true for "scene": "night"
     */
    static void draw(Canvas c, int w, int h, int minute, int sunrise, int sunset, boolean alwaysNight, Kind kind) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float u = w / 960f;
        int rise = sunrise > 0 ? sunrise : Sky.REF_SUNRISE;
        int set = sunset > 0 && sunset > rise ? sunset : Sky.REF_SUNSET;

        // day: 0 at night, 1 in full daylight, ramping over 45 minutes either side of sunrise and sunset.
        float day = alwaysNight ? 0f : daylight(minute, rise, set);
        // warm: how close to sunrise or sunset, for the orange glow and the tint on the hills.
        float warm = alwaysNight ? 0f : Math.max(glowNear(minute, rise), glowNear(minute, set));
        float cover = cover(kind);   // 0 clear sky, 1 fully overcast

        // Sky: the time-of-day palette, pulled to the navy night of the reference after dark, then greyed by cloud.
        // Sky: navy at night, the reference's clear blue by day, the time-of-day palette's warm colours around
        // sunrise and sunset, then greyed by cloud.
        Sky.Palette pal = alwaysNight ? Sky.at(0) : Sky.at(minute, rise, set);
        int top = Sky.lerpColor(NIGHT_TOP, 0xFF4E98CE, day);
        int mid = Sky.lerpColor(NIGHT_MID, 0xFF7AB3DC, day);
        int bottom = Sky.lerpColor(NIGHT_BOTTOM, 0xFFAED3EA, day);
        if (warm > 0f) {
            top = Sky.lerpColor(top, pal.top, warm * 0.5f);
            mid = Sky.lerpColor(mid, pal.mid, warm * 0.8f);
            bottom = Sky.lerpColor(bottom, pal.bottom, warm * 0.9f);
        }
        if (cover > 0f) {
            int greyTop = Sky.lerpColor(0xFF10131B, 0xFF7C8C9C, day);
            int greyLow = Sky.lerpColor(0xFF1A1F2C, 0xFFB2BEC8, day);
            float g = cover * (kind == Kind.STORM ? 0.9f : 0.65f);
            top = Sky.lerpColor(top, greyTop, g);
            mid = Sky.lerpColor(mid, greyLow, g);
            bottom = Sky.lerpColor(bottom, greyLow, g);
        }
        p.setShader(new LinearGradient(0f, 0f, 0f, h, new int[]{top, mid, bottom},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0f, 0f, w, h, p);
        p.setShader(null);

        // Stars.
        float starAlpha = (1f - day) * (1f - cover);
        if (starAlpha > 0.02f) {
            Random r = new Random(55061L);
            for (int i = 0; i < 110; i++) {
                float x = r.nextFloat() * w;
                float y = (float) Math.pow(r.nextFloat(), 1.4) * h * 0.66f;
                float size = (0.45f + r.nextFloat() * r.nextFloat() * 1.3f) * u;
                int alpha = Math.round((70 + r.nextInt(150)) * starAlpha);
                p.setColor((alpha << 24) | 0x00F2F4FF);
                c.drawCircle(x, y, size, p);
            }
        }

        float bodies = cover >= 0.6f ? 0f : 1f - cover * 1.6f;   // overcast hides the sun and moon
        // Sun: along an arc from the left (sunrise) to the right (sunset), orange near the horizon.
        if (day > 0.05f && bodies > 0f) {
            float q = (minute - rise) / (float) (set - rise);
            q = Math.max(0f, Math.min(1f, q));
            float sx = w * (0.12f + 0.76f * q);
            float sy = h * (0.60f - 0.42f * (float) Math.sin(Math.PI * q));
            float sr = 30f * u;
            int core = Sky.lerpColor(0xFFFFF6DA, 0xFFFFA45C, warm);
            int a = Math.round(255 * bodies * Math.min(1f, day * 1.5f));
            p.setShader(new RadialGradient(sx, sy, sr * 4.5f,
                    new int[]{withAlpha(core, a * 0x66 / 255), withAlpha(core, a * 0x22 / 255), core & 0x00FFFFFF},
                    new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
            c.drawCircle(sx, sy, sr * 4.5f, p);
            p.setShader(null);
            p.setColor(withAlpha(core, a));
            c.drawCircle(sx, sy, sr, p);
        }
        // Moon: right of centre, below the top pills.
        if (day < 0.35f && bodies > 0f) {
            float mx = 0.74f * w;
            float my = 0.36f * h;
            float mr = 26f * u;
            float a = (1f - day / 0.35f) * bodies;
            p.setShader(new RadialGradient(mx, my, mr * 4.2f,
                    new int[]{withAlpha(0xFFFFF2CF, Math.round(0x33 * a)), withAlpha(0xFFFFF2CF, Math.round(0x14 * a)),
                            0x00FFF2CF},
                    new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
            c.drawCircle(mx, my, mr * 4.2f, p);
            p.setShader(null);
            Path moon = new Path();
            moon.addCircle(mx, my, mr, Path.Direction.CW);
            Path bite = new Path();
            bite.addCircle(mx + mr * 0.52f, my - mr * 0.22f, mr * 0.92f, Path.Direction.CW);
            moon.op(bite, Path.Op.DIFFERENCE);
            p.setColor(withAlpha(0xFFF8ECC8, Math.round(255 * a)));
            c.drawPath(moon, p);
        }

        // Clouds, behind the hills.
        int clouds = cloudCount(kind);
        if (clouds > 0) {
            int cloudColor = Sky.lerpColor(0xFF39446A, 0xFFF2F4F8, day);
            if (cover > 0.5f) cloudColor = Sky.lerpColor(cloudColor, Sky.lerpColor(0xFF2A3146, 0xFF8E96A3, day), 0.6f);
            if (kind == Kind.STORM) cloudColor = Sky.lerpColor(cloudColor, 0xFF141821, 0.6f);
            cloudColor = Sky.lerpColor(cloudColor, 0xFFE89A86, warm * 0.35f);
            Random r = new Random(9182L);
            for (int i = 0; i < clouds; i++) {
                // Right of the clock and the weather text, which fill the top left.
                float cx = w * 0.44f + (i + 0.2f + r.nextFloat() * 0.5f) / clouds * w * 0.62f;
                float cy = h * (0.08f + r.nextFloat() * 0.30f);
                float scale = (0.7f + r.nextFloat() * 0.6f) * (cover > 0.5f ? 1.3f : 1f) * u;
                int alpha = cover > 0.5f ? 0xC0 : 0x8C;
                cloud(c, p, cx, cy, scale * 1.4f, withAlpha(cloudColor, alpha));
            }
        }

        // Hills: night navy, day green-blue, a warm tint at sunrise and sunset.
        int farC = hill(0xFF1A2A55, 0xFF93BA98, day, warm, cover);
        int midC = hill(0xFF132045, 0xFF6FA07A, day, warm, cover);
        int nearC = hill(0xFF0B1531, 0xFF4F805C, day, warm, cover);

        Path far = new Path();
        far.moveTo(0f, h * 0.66f);
        far.cubicTo(w * 0.18f, h * 0.58f, w * 0.38f, h * 0.70f, w * 0.58f, h * 0.63f);
        far.cubicTo(w * 0.74f, h * 0.575f, w * 0.90f, h * 0.62f, w, h * 0.60f);
        far.lineTo(w, h);
        far.lineTo(0f, h);
        far.close();
        p.setColor(farC);
        c.drawPath(far, p);

        Path midHill = new Path();
        midHill.moveTo(0f, h * 0.78f);
        midHill.cubicTo(w * 0.20f, h * 0.70f, w * 0.42f, h * 0.81f, w * 0.62f, h * 0.765f);
        midHill.cubicTo(w * 0.72f, h * 0.742f, w * 0.88f, h * 0.738f, w, h * 0.755f);
        midHill.lineTo(w, h);
        midHill.lineTo(0f, h);
        midHill.close();
        p.setColor(midC);
        c.drawPath(midHill, p);

        // House on the middle hill's shoulder.
        float hx = 0.80f * w;
        float ground = h * 0.744f;
        float bw = 30f * u;
        float bh = 19f * u;
        float lit = alwaysNight ? 1f : Math.max(0f, Math.min(1f, (0.75f - day) / 0.5f));
        if (lit > 0f) {
            p.setShader(new RadialGradient(hx, ground - bh * 0.5f, 62f * u,
                    new int[]{withAlpha(0xFFFFB55C, Math.round(0x66 * lit)),
                            withAlpha(0xFFFFB55C, Math.round(0x22 * lit)), 0x00FFB55C},
                    new float[]{0f, 0.4f, 1f}, Shader.TileMode.CLAMP));
            c.drawCircle(hx, ground - bh * 0.5f, 62f * u, p);
            p.setShader(null);
        }
        int silhouette = Sky.lerpColor(0xFF060B1C, 0xFF3E6C4F, day);
        int wall = silhouette;
        int roofC = silhouette;
        int pineC = silhouette;

        p.setColor(pineC);
        pine(c, p, hx - bw * 1.25f, ground + 2f * u, 9f * u, 30f * u);
        pine(c, p, hx + bw * 1.15f, ground + 2f * u, 8f * u, 24f * u);
        pine(c, p, hx + bw * 1.65f, ground + 3f * u, 10f * u, 34f * u);
        p.setColor(roofC);
        c.drawRect(hx + bw * 0.18f, ground - bh - bw * 0.42f, hx + bw * 0.34f, ground - bh, p);
        p.setColor(wall);
        c.drawRect(hx - bw / 2f, ground - bh, hx + bw / 2f, ground + 2f * u, p);
        p.setColor(roofC);
        Path roof = new Path();
        roof.moveTo(hx - bw * 0.66f, ground - bh + 1f * u);
        roof.lineTo(hx, ground - bh - bw * 0.46f);
        roof.lineTo(hx + bw * 0.66f, ground - bh + 1f * u);
        roof.close();
        c.drawPath(roof, p);
        // Windows: lit gold after dusk, dark glass by day.
        p.setColor(Sky.lerpColor(0xFF27463A, 0xFFFFC870, lit));
        float ww = 6.5f * u;
        float wy = ground - bh * 0.72f;
        c.drawRect(hx - bw * 0.30f, wy, hx - bw * 0.30f + ww, wy + ww, p);
        c.drawRect(hx + bw * 0.30f - ww, wy, hx + bw * 0.30f, wy + ww, p);
        // Chimney smoke, only when the fire is lit.
        if (lit > 0.3f && kind != Kind.RAIN && kind != Kind.STORM) {
            float sx = hx + bw * 0.26f;
            float sy = ground - bh - bw * 0.42f;
            p.setColor(0x33C9D3F2);
            c.drawCircle(sx - 1f * u, sy - 7f * u, 3.2f * u, p);
            p.setColor(0x26C9D3F2);
            c.drawCircle(sx - 4f * u, sy - 17f * u, 4.4f * u, p);
            p.setColor(0x1AC9D3F2);
            c.drawCircle(sx - 2f * u, sy - 29f * u, 5.6f * u, p);
        }

        // Fog: a pale band settling over the hills.
        if (kind == Kind.FOG) {
            int fog = Sky.lerpColor(0xFF4A5470, 0xFFE4E8EE, day);
            p.setShader(new LinearGradient(0f, h * 0.50f, 0f, h * 0.92f,
                    new int[]{fog & 0x00FFFFFF, withAlpha(fog, 0x8C), withAlpha(fog, 0x40)},
                    new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
            c.drawRect(0f, h * 0.50f, w, h, p);
            p.setShader(null);
        }

        Path near = new Path();
        near.moveTo(0f, h * 0.90f);
        near.cubicTo(w * 0.28f, h * 0.83f, w * 0.55f, h * 0.93f, w * 0.78f, h * 0.875f);
        near.cubicTo(w * 0.88f, h * 0.85f, w * 0.95f, h * 0.86f, w, h * 0.865f);
        near.lineTo(w, h);
        near.lineTo(0f, h);
        near.close();
        p.setColor(nearC);
        c.drawPath(near, p);

        // Rain, drizzle or snow over everything.
        if (kind == Kind.RAIN || kind == Kind.STORM || kind == Kind.DRIZZLE) {
            Random r = new Random(4410L);
            int n = kind == Kind.DRIZZLE ? 90 : 190;
            float len = (kind == Kind.DRIZZLE ? 7f : 15f) * u;
            p.setStrokeWidth(Math.max(1f, 0.9f * u));
            p.setStyle(Paint.Style.STROKE);
            p.setColor(Sky.lerpColor(0x40A9B8D8, 0x4CE8EEF8, day));
            for (int i = 0; i < n; i++) {
                float x = r.nextFloat() * (w + 40f * u);
                float y = r.nextFloat() * h;
                c.drawLine(x, y, x - len * 0.28f, y + len, p);
            }
            p.setStyle(Paint.Style.FILL);
        } else if (kind == Kind.SNOW) {
            Random r = new Random(4410L);
            p.setColor(0xCCF4F7FF);
            for (int i = 0; i < 140; i++) {
                c.drawCircle(r.nextFloat() * w, r.nextFloat() * h, (0.8f + r.nextFloat() * 1.4f) * u, p);
            }
        }
    }

    private static float daylight(int m, int rise, int set) {
        final float t = 45f;
        if (m <= rise - t || m >= set + t) return 0f;
        if (m >= rise + t && m <= set - t) return 1f;
        if (m < rise + t) return (m - (rise - t)) / (2 * t);
        return ((set + t) - m) / (2 * t);
    }

    private static float glowNear(int m, int event) {
        return Math.max(0f, 1f - Math.abs(m - event) / 70f);
    }

    private static float cover(Kind k) {
        switch (k) {
            case PARTLY: return 0.15f;
            case CLOUDY: case DRIZZLE: case SNOW: return 0.8f;
            case FOG: return 0.6f;
            case RAIN: return 0.9f;
            case STORM: return 1f;
            default: return 0f;
        }
    }

    private static int cloudCount(Kind k) {
        switch (k) {
            case PARTLY: return 3;
            case FOG: return 2;
            case CLOUDY: case DRIZZLE: case SNOW: return 4;
            case RAIN: case STORM: return 5;
            default: return 0;
        }
    }

    private static int hill(int night, int dayColor, float day, float warm, float cover) {
        int c = Sky.lerpColor(night, dayColor, day);
        c = Sky.lerpColor(c, 0xFF3B2546, warm * 0.35f);
        return Sky.lerpColor(c, Sky.lerpColor(night, 0xFF4A5560, day), cover * 0.35f);
    }

    private static int withAlpha(int color, int alpha) {
        return (Math.max(0, Math.min(255, alpha)) << 24) | (color & 0x00FFFFFF);
    }

    /** A flat cloud: a long soft lens with a smaller one overlapping its upper right, as in the reference. */
    private static void cloud(Canvas c, Paint p, float x, float y, float s, int color) {
        p.setColor(color);
        c.drawOval(x - 52f * s, y - 9f * s, x + 52f * s, y + 9f * s, p);
        c.drawOval(x - 4f * s, y - 16f * s, x + 52f * s, y + 3f * s, p);
    }

    /** A simple three-tier pine standing on {@code base}, centred on {@code x}. */
    private static void pine(Canvas c, Paint p, float x, float base, float halfWidth, float height) {
        Path t = new Path();
        for (int tier = 0; tier < 3; tier++) {
            float top = base - height + tier * height * 0.26f;
            float bottom = base - height * 0.30f + tier * height * 0.15f;
            float half = halfWidth * (0.55f + tier * 0.25f);
            t.moveTo(x, top);
            t.lineTo(x + half, bottom);
            t.lineTo(x - half, bottom);
            t.close();
        }
        c.drawPath(t, p);
        c.drawRect(x - halfWidth * 0.12f, base - height * 0.12f, x + halfWidth * 0.12f, base, p);
    }
}
