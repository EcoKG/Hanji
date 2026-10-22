package io.hanji.examples.todo;

import io.hanji.HanjiApplication;
import io.hanji.Window;
import io.hanji.menu.Menu;
import io.hanji.menu.MenuBar;
import io.hanji.menu.MenuItem;
import io.hanji.menu.MenuRole;

/** HTML page + controller (FXML style). Run: mvn -pl hanji-examples exec:java -Dexample=todo */
public class TodoApp extends HanjiApplication {

    private final TodoStore store = new TodoStore();

    @Override
    public void init() {
        store.add("Hanji 예제 실행해 보기", Todo.Priority.HIGH);
        store.add("HTML/CSS로 화면 꾸미기", Todo.Priority.NORMAL);
        store.add("더블클릭으로 제목 수정", Todo.Priority.LOW).setDone(true);
    }

    @Override
    public void start(Window window) {
        window.setTitle("Hanji Todo")
                .setSize(560, 720)
                .setMinimumSize(420, 480)
                .setMenuBar(new MenuBar(
                        new Menu("파일",
                                new MenuItem("새 할 일", "CmdOrCtrl+N", () -> window.byId("title").focus()),
                                new MenuItem("완료 항목 삭제", "CmdOrCtrl+Shift+Backspace",
                                        () -> window.<TodoController>getController().clearDone()),
                                MenuItem.separator(),
                                MenuItem.role(MenuRole.QUIT, "종료")),
                        Menu.standard(MenuRole.EDIT_MENU, "편집"),
                        new Menu("보기",
                                MenuItem.checkbox("항상 위에 표시", false, window::setAlwaysOnTop),
                                MenuItem.role(MenuRole.TOGGLE_FULL_SCREEN, "전체 화면"))))
                .load("todo/index.html", () -> new TodoController(store))
                .show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
