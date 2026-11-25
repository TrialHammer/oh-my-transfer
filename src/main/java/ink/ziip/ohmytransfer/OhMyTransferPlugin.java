package ink.ziip.ohmytransfer;

import ink.ziip.ohmytransfer.command.OmtCommand;
import ink.ziip.ohmytransfer.command.OmtCommandRegistration;
import ink.ziip.ohmytransfer.command.SuggestionFilterListener;
import ink.ziip.ohmytransfer.config.ConfigUpdater;
import ink.ziip.ohmytransfer.config.Settings;
import ink.ziip.ohmytransfer.listener.TicketVerifyListener;
import ink.ziip.ohmytransfer.map.MapCompatListener;
import ink.ziip.ohmytransfer.ticket.NonceCache;
import ink.ziip.ohmytransfer.ticket.TransferTicketService;
import ink.ziip.ohmytransfer.wormholes.WormholesBridge;
import org.bukkit.configuration.Configuration;
import org.bukkit.plugin.java.JavaPlugin;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Set;

public final class OhMyTransferPlugin extends JavaPlugin {

    private static final long PURGE_INTERVAL_TICKS = 20L * 60;
    /** Config sections whose entries are user data; the bundled examples must not be merged into them. */
    private static final Set<String> USER_OWNED_CONFIG_SECTIONS = Set.of("servers");

    private final NonceCache nonces = new NonceCache();
    private volatile Settings settings;
    private MapCompatListener mapCompatListener;
    private WormholesBridge wormholesBridge;
    private OmtCommandRegistration commandRegistration;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        try {
            reloadSettings();
        } catch (IllegalArgumentException e) {
            getLogger().severe("Invalid config.yml: " + e.getMessage() + " - disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        if (!getServer().isAcceptingTransfers()) {
            getLogger().warning("accepts-transfers=false in server.properties: this server can send players "
                    + "with /omt but cannot receive transferred players.");
        }

        TicketVerifyListener listener = new TicketVerifyListener(this, nonces);
        getServer().getPluginManager().registerEvents(listener, this);
        SuggestionFilterListener suggestionFilter = new SuggestionFilterListener(this);
        getServer().getPluginManager().registerEvents(suggestionFilter, this);
        mapCompatListener = new MapCompatListener(this);
        getServer().getPluginManager().registerEvents(mapCompatListener, this);
        mapCompatListener.reload();

        TransferTicketService tickets = new TransferTicketService();
        wormholesBridge = new WormholesBridge(this, tickets);
        getServer().getPluginManager().registerEvents(wormholesBridge, this);
        wormholesBridge.reload();

        commandRegistration = new OmtCommandRegistration(this, new OmtCommand(this, tickets), suggestionFilter);
        commandRegistration.start();

        getServer().getScheduler().runTaskTimerAsynchronously(this, () -> {
            long now = System.currentTimeMillis();
            nonces.purgeExpired(now);
            listener.purgeStaleCookieClears(now);
        }, PURGE_INTERVAL_TICKS, PURGE_INTERVAL_TICKS);
    }

    /**
     * Reloads config.yml. The previous settings stay active if the new configuration is invalid.
     *
     * @throws IllegalArgumentException if the configuration is invalid
     */
    public void reloadSettings() {
        reloadConfig();
        boolean addedKeys = addMissingConfigKeys();
        boolean generatedSecret = ensureSecret();
        if (addedKeys || generatedSecret) {
            saveConfig();
        }
        settings = Settings.load(getConfig());
        if (mapCompatListener != null) {
            mapCompatListener.reload();
        }
        if (wormholesBridge != null) {
            wormholesBridge.reload();
        }
    }

    @Override
    public void onDisable() {
        if (commandRegistration != null) {
            commandRegistration.stop();
            commandRegistration = null;
        }
        if (wormholesBridge != null) {
            wormholesBridge.shutdown();
            wormholesBridge = null;
        }
        if (mapCompatListener != null) {
            mapCompatListener.shutdown();
        }
    }

    public Settings settings() {
        return settings;
    }

    /** Adds options introduced by newer plugin versions to an existing config.yml. */
    private boolean addMissingConfigKeys() {
        Configuration defaults = getConfig().getDefaults();
        if (defaults == null) {
            return false;
        }
        List<String> added = ConfigUpdater.addMissingKeys(getConfig(), defaults, USER_OWNED_CONFIG_SECTIONS);
        if (added.isEmpty()) {
            return false;
        }
        getLogger().info("Added new options to config.yml: " + String.join(", ", added));
        return true;
    }

    private boolean ensureSecret() {
        if (!getConfig().getString("secret", "").isBlank()) {
            return false;
        }
        byte[] secret = new byte[Settings.MIN_SECRET_BYTES];
        new SecureRandom().nextBytes(secret);
        getConfig().set("secret", Base64.getEncoder().encodeToString(secret));
        getLogger().warning("Generated a new random secret in config.yml. Copy the same secret to every "
                + "server that sends or receives transfers, otherwise tickets will be rejected.");
        return true;
    }
}
