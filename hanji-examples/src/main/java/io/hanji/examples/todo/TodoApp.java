package io.hanji.examples.todo;

import io.hanji.HanjiApplication;
import io.hanji.Window;

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
                .load("todo/index.html", () -> new TodoController(store))
                .show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
