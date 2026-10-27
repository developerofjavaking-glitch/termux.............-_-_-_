package com.example.termuxlite;

import android.content.Context;
import android.content.SharedPreferences;

import com.termux.terminal.TerminalColorScheme;
import com.termux.terminal.TerminalColors;
import com.termux.terminal.TerminalSession;

import java.util.List;
import java.util.Properties;

/**
 * Manages terminal color schemes: Default, Dracula, Solarized Dark, Monokai, and Light.
 */
public class ColorThemeHelper {

    public static final String THEME_DEFAULT = "default";
    public static final String THEME_DRACULA = "dracula";
    public static final String THEME_SOLARIZED = "solarized";
    public static final String THEME_MONOKAI = "monokai";
    public static final String THEME_LIGHT = "light";

    public static Properties getThemeProperties(String themeKey) {
        Properties props = new Properties();

        switch (themeKey) {
            case THEME_DRACULA:
                props.setProperty("foreground", "#f8f8f2");
                props.setProperty("background", "#282a36");
                props.setProperty("cursor", "#f8f8f2");
                props.setProperty("color0", "#21222c");
                props.setProperty("color1", "#ff5555");
                props.setProperty("color2", "#50fa7b");
                props.setProperty("color3", "#f1fa8c");
                props.setProperty("color4", "#bd93f9");
                props.setProperty("color5", "#ff79c6");
                props.setProperty("color6", "#8be9fd");
                props.setProperty("color7", "#f8f8f2");
                props.setProperty("color8", "#6272a4");
                props.setProperty("color9", "#ff6e6e");
                props.setProperty("color10", "#69ff94");
                props.setProperty("color11", "#ffffa5");
                props.setProperty("color12", "#d6acff");
                props.setProperty("color13", "#ff92df");
                props.setProperty("color14", "#a4ffff");
                props.setProperty("color15", "#ffffff");
                break;

            case THEME_SOLARIZED:
                props.setProperty("foreground", "#839496");
                props.setProperty("background", "#002b36");
                props.setProperty("cursor", "#93a1a1");
                props.setProperty("color0", "#073642");
                props.setProperty("color1", "#dc322f");
                props.setProperty("color2", "#859900");
                props.setProperty("color3", "#b58900");
                props.setProperty("color4", "#268bd2");
                props.setProperty("color5", "#d33682");
                props.setProperty("color6", "#2aa198");
                props.setProperty("color7", "#eee8d5");
                props.setProperty("color8", "#002b36");
                props.setProperty("color9", "#cb4b16");
                props.setProperty("color10", "#586e75");
                props.setProperty("color11", "#657b83");
                props.setProperty("color12", "#839496");
                props.setProperty("color13", "#6c71c4");
                props.setProperty("color14", "#93a1a1");
                props.setProperty("color15", "#fdf6e3");
                break;

            case THEME_MONOKAI:
                props.setProperty("foreground", "#f8f8f2");
                props.setProperty("background", "#272822");
                props.setProperty("cursor", "#f8f8f0");
                props.setProperty("color0", "#272822");
                props.setProperty("color1", "#f92672");
                props.setProperty("color2", "#a6e22e");
                props.setProperty("color3", "#f4bf75");
                props.setProperty("color4", "#66d9ef");
                props.setProperty("color5", "#ae81ff");
                props.setProperty("color6", "#a1efe4");
                props.setProperty("color7", "#f8f8f2");
                props.setProperty("color8", "#75715e");
                props.setProperty("color9", "#f92672");
                props.setProperty("color10", "#a6e22e");
                props.setProperty("color11", "#f4bf75");
                props.setProperty("color12", "#66d9ef");
                props.setProperty("color13", "#ae81ff");
                props.setProperty("color14", "#a1efe4");
                props.setProperty("color15", "#f9f8f5");
                break;

            case THEME_LIGHT:
                props.setProperty("foreground", "#000000");
                props.setProperty("background", "#ffffff");
                props.setProperty("cursor", "#000000");
                props.setProperty("color0", "#000000");
                props.setProperty("color1", "#c00000");
                props.setProperty("color2", "#008000");
                props.setProperty("color3", "#808000");
                props.setProperty("color4", "#0000c0");
                props.setProperty("color5", "#c000c0");
                props.setProperty("color6", "#008080");
                props.setProperty("color7", "#c0c0c0");
                props.setProperty("color8", "#808080");
                props.setProperty("color9", "#ff0000");
                props.setProperty("color10", "#00ff00");
                props.setProperty("color11", "#ffff00");
                props.setProperty("color12", "#0000ff");
                props.setProperty("color13", "#ff00ff");
                props.setProperty("color14", "#00ffff");
                props.setProperty("color15", "#ffffff");
                break;

            case THEME_DEFAULT:
            default:
                // Standard default Termux colors
                props.setProperty("foreground", "#ffffff");
                props.setProperty("background", "#000000");
                props.setProperty("cursor", "#ffffff");
                break;
        }

        return props;
    }

    public static void applyTheme(Context context, String themeKey, List<TerminalSession> activeSessions) {
        Properties props = getThemeProperties(themeKey);
        TerminalColors.COLOR_SCHEME.updateWith(props);

        if (activeSessions != null) {
            for (TerminalSession session : activeSessions) {
                if (session != null && session.getEmulator() != null) {
                    session.getEmulator().mColors.reset();
                    session.onColorsChanged();
                }
            }
        }
    }

    public static void loadAndApplySavedTheme(Context context, List<TerminalSession> activeSessions) {
        SharedPreferences prefs = context.getSharedPreferences("boomaux_prefs", Context.MODE_PRIVATE);
        String theme = prefs.getString("color_scheme", THEME_DEFAULT);
        applyTheme(context, theme, activeSessions);
    }
}
