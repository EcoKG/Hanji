package io.hanji.menu;

/** Something that contains menu items and must be told when they change. */
abstract class MenuNode {

    MenuNode parent;

    void changed() {
        if (parent != null) {
            parent.changed();
        }
    }

    void adopt(MenuNode child) {
        child.parent = this;
    }
}
