package io.hanji.menu;

/** Standard menu items implemented by the platform (labels, shortcuts and behaviour follow the OS). */
public enum MenuRole {
    UNDO("undo"), REDO("redo"), CUT("cut"), COPY("copy"), PASTE("paste"), SELECT_ALL("selectAll"), DELETE("delete"),
    MINIMIZE("minimize"), CLOSE("close"), QUIT("quit"), TOGGLE_FULL_SCREEN("togglefullscreen"),
    RELOAD("reload"), TOGGLE_DEV_TOOLS("toggleDevTools"), RESET_ZOOM("resetZoom"), ZOOM_IN("zoomIn"), ZOOM_OUT("zoomOut"),
    ABOUT("about"), HIDE("hide"), HIDE_OTHERS("hideOthers"), UNHIDE("unhide"),
    /** A complete standard menu (usable as a top-level {@link Menu}). */
    EDIT_MENU("editMenu"), VIEW_MENU("viewMenu"), WINDOW_MENU("windowMenu");

    final String id;

    MenuRole(String id) {
        this.id = id;
    }
}
