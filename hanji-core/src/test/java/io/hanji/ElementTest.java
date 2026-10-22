package io.hanji;

import io.hanji.ui.ListView;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ElementTest {

    @Test
    void detachedElementRecordsCommandsInOrder() {
        Element div = Element.create("div").setText("hi").addClass("a").setStyle("fontSize", "12px");
        Element child = Element.create("span");
        div.append(child);
        List<Map<String, Object>> cmds = div.pendingCommands();
        assertEquals(List.of("set", "cls", "style", "append"), cmds.stream().map(c -> c.get("op")).toList());
        assertEquals("font-size", cmds.get(2).get("name"));
        assertEquals(child.getId(), cmds.get(3).get("child"));
        assertTrue(div.getId().startsWith("hj-"));
    }

    @Test
    void localFormStateWhileDetached() {
        Element input = Element.create("input").setValue("abc").setChecked(true);
        assertEquals("abc", input.getValue());
        assertTrue(input.isChecked());
    }

    @Test
    void cssNameConversion() {
        assertEquals("background-color", Element.cssName("backgroundColor"));
        assertEquals("--my-var", Element.cssName("--my-var"));
        assertEquals("margin-top", Element.cssName("margin-top"));
    }

    @Test
    void listViewRendersItemsIntoDetachedElement() {
        ListView<String> list = new ListView<>(List.of("apple", "banana"));
        List<Map<String, Object>> cmds = list.element().pendingCommands();
        long appends = cmds.stream().filter(c -> "append".equals(c.get("op"))).count();
        assertEquals(2, appends);
    }

    @Test
    void injectsClientRuntimeIntoHead() {
        String html = BridgeServer.inject("<!DOCTYPE html><html><HEAD><title>x</title></HEAD><body><header></header></body></html>", "tok", false);
        assertTrue(html.startsWith("<!DOCTYPE html><html><HEAD><script src=\"/tok/__hanji/hanji.js\"></script>"), html);
        String noHead = BridgeServer.inject("<p>hi</p>", "tok", true);
        assertTrue(noHead.startsWith("<script src=\"/tok/__hanji/hanji.js?dev=1\">"), noHead);
    }
}
