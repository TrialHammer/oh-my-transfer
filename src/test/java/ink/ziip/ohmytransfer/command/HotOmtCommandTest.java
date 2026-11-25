package ink.ziip.ohmytransfer.command;

import org.junit.jupiter.api.Test;

import static ink.ziip.ohmytransfer.command.HotOmtCommand.Action.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

class HotOmtCommandTest {

    @Test
    void prefersSelfTransferForSingleServerArgument() {
        assertEquals(SELF, HotOmtCommand.route(new String[]{"survival"}, true, true));
        assertEquals(SELF, HotOmtCommand.route(new String[]{"survival"}, false, true));
        assertEquals(USAGE, HotOmtCommand.route(new String[]{"survival"}, true, false));
    }

    @Test
    void keepsAdminCommandsSeparateFromSelfTransfer() {
        assertEquals(RELOAD, HotOmtCommand.route(new String[]{"reload"}, true, false));
        assertEquals(DENIED, HotOmtCommand.route(new String[]{"reload"}, false, true));
        assertEquals(USAGE, HotOmtCommand.route(new String[]{"reload", "survival"}, true, true));
        assertEquals(OTHER, HotOmtCommand.route(new String[]{"@p", "survival"}, true, false));
        assertEquals(DENIED, HotOmtCommand.route(new String[]{"Steve", "survival"}, false, true));
    }
}
