package ink.ziip.ohmytransfer.listener;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.geysermc.floodgate.api.FloodgateApi;

import java.util.UUID;
import java.util.function.Predicate;

/**
 * Optional Floodgate integration. Floodgate types are only touched after the plugin is present, so
 * OhMyTransfer still loads on servers without Floodgate.
 */
final class FloodgateSupport {

    private final Predicate<UUID> floodgatePlayerCheck;

    FloodgateSupport(Predicate<UUID> floodgatePlayerCheck) {
        this.floodgatePlayerCheck = floodgatePlayerCheck;
    }

    /** Returns the integration, or {@code null} when Floodgate is not installed. */
    static FloodgateSupport detect() {
        Plugin floodgate = Bukkit.getPluginManager().getPlugin("floodgate");
        if (floodgate == null || !floodgate.isEnabled()) {
            return null;
        }
        try {
            FloodgateApi api = FloodgateApi.getInstance();
            if (api == null) {
                Bukkit.getLogger().warning("Floodgate is installed but its API is not ready; "
                        + "Floodgate ticket bypass is disabled.");
                return null;
            }
            return new FloodgateSupport(api::isFloodgatePlayer);
        } catch (LinkageError | RuntimeException e) {
            Bukkit.getLogger().warning("Floodgate is installed but its API is unavailable: " + e);
            return null;
        }
    }

    boolean isFloodgatePlayer(UUID playerId) {
        return floodgatePlayerCheck.test(playerId);
    }
}
