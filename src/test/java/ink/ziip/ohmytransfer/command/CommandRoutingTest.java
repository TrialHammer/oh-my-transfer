package ink.ziip.ohmytransfer.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.Suggestions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Mirrors the shape of {@link OmtCommand#build()} with plain Brigadier, because Paper's player argument
 * cannot be created outside a running server. It pins down the parse precedence the real tree relies on:
 * {@code /omt <server>} and {@code /omt <player> <server>} share the first argument position.
 */
class CommandRoutingTest {

    private record Source(Set<String> permissions) {
        boolean has(String permission) {
            return permissions.contains(permission);
        }
    }

    private static final Source ADMIN_AND_SELF = new Source(Set.of(OmtCommand.ADMIN_PERMISSION, OmtCommand.SELF_PERMISSION));
    private static final Source ADMIN = new Source(Set.of(OmtCommand.ADMIN_PERMISSION));
    private static final Source SELF = new Source(Set.of(OmtCommand.SELF_PERMISSION));
    private static final Source NOBODY = new Source(Set.of());

    private final List<String> calls = new ArrayList<>();
    private final CommandDispatcher<Source> dispatcher = new CommandDispatcher<>();

    CommandRoutingTest() {
        dispatcher.register(LiteralArgumentBuilder.<Source>literal("omt")
                .requires(s -> s.has(OmtCommand.ADMIN_PERMISSION) || s.has(OmtCommand.SELF_PERMISSION))
                .then(LiteralArgumentBuilder.<Source>literal("reload")
                        .requires(s -> s.has(OmtCommand.ADMIN_PERMISSION))
                        .executes(ctx -> record("reload")))
                .then(server()
                        .requires(s -> s.has(OmtCommand.SELF_PERMISSION))
                        .executes(ctx -> record("self " + StringArgumentType.getString(ctx, "server"))))
                .then(RequiredArgumentBuilder.<Source, String>argument("player", StringArgumentType.word())
                        .requires(s -> s.has(OmtCommand.ADMIN_PERMISSION))
                        .suggests((ctx, b) -> b.suggest("Steve").buildFuture())
                        .then(server().executes(ctx -> record("other " + StringArgumentType.getString(ctx, "player")
                                + " " + StringArgumentType.getString(ctx, "server"))))));
    }

    private static RequiredArgumentBuilder<Source, String> server() {
        return RequiredArgumentBuilder.<Source, String>argument("server", StringArgumentType.word())
                .suggests((ctx, b) -> b.suggest("survival").buildFuture());
    }

    private int record(String call) {
        calls.add(call);
        return 1;
    }

    private String run(String command, Source source) throws CommandSyntaxException {
        calls.clear();
        dispatcher.execute(command, source);
        return String.join(",", calls);
    }

    private List<String> suggest(String command, Source source) {
        return dispatcher.getCompletionSuggestions(dispatcher.parse(command, source)).join()
                .getList().stream().map(Suggestion::getText).sorted().toList();
    }

    @Test
    void routesBothFormsForSourceWithBothPermissions() throws Exception {
        assertEquals("self survival", run("omt survival", ADMIN_AND_SELF));
        assertEquals("other Steve survival", run("omt Steve survival", ADMIN_AND_SELF));
        assertEquals("reload", run("omt reload", ADMIN_AND_SELF));
    }

    @Test
    void selfOnlySourceCannotUseAdminForms() throws Exception {
        assertEquals("self survival", run("omt survival", SELF));
        assertThrows(CommandSyntaxException.class, () -> run("omt Steve survival", SELF));
        // A matching literal shadows the argument branches even without permission, which is why
        // "reload" is a reserved server name.
        assertThrows(CommandSyntaxException.class, () -> run("omt reload", SELF));
    }

    @Test
    void adminOnlySourceCannotUseSelfForm() throws Exception {
        assertEquals("other Steve survival", run("omt Steve survival", ADMIN));
        assertThrows(CommandSyntaxException.class, () -> run("omt survival", ADMIN));
    }

    @Test
    void noPermissionHidesCommand() {
        assertThrows(CommandSyntaxException.class, () -> run("omt survival", NOBODY));
        assertEquals(List.of(), suggest("omt ", NOBODY));
    }

    @Test
    void serverSideSuggestionsIgnoreRequires() {
        // Brigadier lists every sibling's suggestions regardless of permission. This is what the server answers
        // when the client asks for <server> suggestions, hence SuggestionFilterListener.
        assertEquals(List.of("Steve", "reload", "survival"), suggest("omt ", SELF));
        assertEquals(List.of("survival"), suggest("omt Steve ", ADMIN));
    }

    @Test
    void filterLeavesOnlyServersForSelfOnlyPlayers() {
        Suggestions leaked = dispatcher.getCompletionSuggestions(dispatcher.parse("omt ", SELF)).join();
        Set<String> labels = Set.of("omt", "ohmytransfer:omt", "server", "ohmytransfer:server");
        Set<String> servers = Set.of("survival");
        for (String input : List.of("/omt ", "/OhMyTransfer:omt su", "/server ", "/ohmytransfer:server ")) {
            Suggestions filtered = SuggestionFilterListener.restrictToServers(input, leaked, labels, servers);
            assertEquals(List.of("survival"), filtered.getList().stream().map(Suggestion::getText).toList(), input);
        }
        for (String input : List.of("/omtx ", "/servers ", "/other:server ", "/tp ", "/server")) {
            assertNull(SuggestionFilterListener.restrictToServers(input, leaked, labels, servers), input);
        }
        assertNull(SuggestionFilterListener.restrictToServers("/omt ", leaked, labels,
                Set.of("survival", "reload", "Steve")));
    }

    @Test
    void filterLeavesServerAliasAloneWhenNotRegistered() {
        Suggestions leaked = dispatcher.getCompletionSuggestions(dispatcher.parse("omt ", SELF)).join();
        assertNull(SuggestionFilterListener.restrictToServers("/server ", leaked,
                Set.of("omt", "ohmytransfer:omt", "ohmytransfer:server"), Set.of("survival")));
    }
}
