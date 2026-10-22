package io.hanji.examples.components;

import io.hanji.HanjiApplication;
import io.hanji.Window;
import io.hanji.property.Bindings;
import io.hanji.ui.Button;
import io.hanji.ui.CheckBox;
import io.hanji.ui.ComboBox;
import io.hanji.ui.HBox;
import io.hanji.ui.Label;
import io.hanji.ui.ListView;
import io.hanji.ui.TableView;
import io.hanji.ui.TextField;
import io.hanji.ui.VBox;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;

/**
 * A UI built entirely in Java (Swing style): no HTML file, built-in components and property bindings.
 * Run: mvn -pl hanji-examples exec:java -Dexample=components
 */
public class ComponentsApp extends HanjiApplication {

    record Employee(String name, String department, int age, boolean remote) {
    }

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    @Override
    public void start(Window window) {
        TextField name = new TextField().setPlaceholder("이름");
        ComboBox<String> department = new ComboBox<>(List.of("개발", "디자인", "영업", "인사"))
                .setPlaceholder("부서 선택");
        TextField age = new TextField().setPlaceholder("나이");
        age.element().setAttribute("type", "number").setStyle("width", "90px");
        CheckBox remote = new CheckBox("재택 근무");
        Button add = new Button("추가").setPrimary(true);
        Button remove = new Button("삭제");

        TableView<Employee> table = new TableView<>();
        table.addColumn("이름", Employee::name);
        table.addColumn("부서", Employee::department);
        table.addColumn("나이", Employee::age).setAlign("right").setWidth("80px");
        table.addColumn("근무", e -> e.remote() ? "재택" : "사무실").setWidth("90px");
        table.setPlaceholder("직원이 없습니다");
        table.getItems().setAll(
                new Employee("김민준", "개발", 31, true),
                new Employee("이서연", "디자인", 28, false),
                new Employee("박지훈", "영업", 42, false));

        ListView<String> log = new ListView<String>().setPlaceholder("이벤트 로그");
        log.setStyle("width", "260px");
        Label status = new Label();

        // Bindings: enable/disable and texts follow the model automatically
        add.bindEnabled(Bindings.create(
                () -> !name.getText().isBlank() && department.getValue() != null,
                name.textProperty(), department.valueProperty()));
        remove.bindEnabled(table.selectedItemProperty().map(Objects::nonNull));
        status.bindText(Bindings.format("직원 %d명", table.getItems().sizeProperty()));

        Runnable addEmployee = () -> {
            if (name.getText().isBlank() || department.getValue() == null) {
                return;
            }
            int years;
            try {
                years = Integer.parseInt(age.getText().trim());
            } catch (NumberFormatException e) {
                years = 0;
            }
            Employee employee = new Employee(name.getText().trim(), department.getValue(), years, remote.isSelected());
            table.getItems().add(employee);
            log.getItems().add(0, now() + " 추가: " + employee.name());
            name.setText("");
            age.setText("");
            name.focus();
        };
        add.setOnAction(e -> addEmployee.run());
        name.setOnAction(e -> addEmployee.run()); // Enter key

        remove.setOnAction(e -> {
            Employee selected = table.getSelectedItem();
            window.confirm("직원 삭제", selected.name() + " 님을 삭제할까요?").thenAccept(ok -> {
                if (ok) {
                    table.getItems().remove(selected);
                    log.getItems().add(0, now() + " 삭제: " + selected.name());
                }
            });
        });
        table.setOnRowActivated(emp -> window.alert(emp.name(),
                emp.department() + " / " + emp.age() + "세 / " + (emp.remote() ? "재택" : "사무실")));
        table.selectedItemProperty().addListener((obs, oldValue, newValue) -> {
            if (newValue != null) {
                log.getItems().add(0, now() + " 선택: " + newValue.name());
            }
        });

        VBox root = new VBox(
                Label.heading("직원 관리"),
                new HBox(name.setGrow(true), department, age, remote, add, remove),
                new HBox(table.setGrow(true), log).setAlignment("stretch"),
                new Label("행을 더블클릭하면 상세 정보를 표시합니다. 헤더를 클릭하면 정렬합니다.")
                        .setStyle("color", "var(--hj-muted)"),
                status);
        root.setGap(12);

        // ask before closing when the list was changed (needs a host with native controls, e.g. electron)
        boolean[] dirty = {false};
        table.getItems().addListener(list -> dirty[0] = true);
        window.setOnCloseRequest(request -> {
            if (dirty[0]) {
                request.cancel();
                window.confirm("닫기", "변경한 직원 목록이 저장되지 않았습니다. 닫을까요?").thenAccept(ok -> {
                    if (ok) {
                        window.close();
                    }
                });
            }
        });

        window.setTitle("Hanji Components").setSize(980, 640).setContent(root).show();
        name.focus();
    }

    private static String now() {
        return LocalTime.now().format(TIME);
    }

    public static void main(String[] args) {
        launch(args);
    }
}
