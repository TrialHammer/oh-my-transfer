package ink.ziip.ohmytransfer.wormholes;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/** Loads the optional public AuthMe API from AuthMe's own class loader. */
final class AuthMeAccess {

    private static final String API = "fr.xephi.authme.api.v3.AuthMeApi";

    private AuthMeAccess() {
    }

    static boolean authenticated(Plugin authMe, Player player) throws ReflectiveOperationException {
        if (authMe == null) {
            return true;
        }
        if (!authMe.isEnabled()) {
            throw new IllegalStateException("AuthMe is installed but disabled");
        }
        Class<?> api = Class.forName(API, true, authMe.getClass().getClassLoader());
        Object instance = api.getMethod("getInstance").invoke(null);
        if (instance == null) {
            throw new IllegalStateException("AuthMe API instance is unavailable");
        }
        return (boolean) api.getMethod("isAuthenticated", Player.class).invoke(instance, player);
    }
}
