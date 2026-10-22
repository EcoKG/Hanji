package io.hanji.examples.showcase;

import io.hanji.Element;
import io.hanji.Hanji;
import io.hanji.UiField;
import io.hanji.UiHandler;
import io.hanji.Window;
import io.hanji.menu.Menu;
import io.hanji.menu.MenuBar;
import io.hanji.menu.MenuItem;
import io.hanji.menu.MenuRole;

/** Controller of {@code web/showcase/login.html}: validation, (simulated) authentication off the UI thread, page change. */
public class LoginController {

    @UiField Window window;
    @UiField Element loginForm;
    @UiField Element username;
    @UiField Element password;
    @UiField Element userField;
    @UiField Element passField;
    @UiField Element userError;
    @UiField Element passError;
    @UiField Element loginError;
    @UiField Element loginErrorText;
    @UiField Element loginBtn;
    @UiField Element remember;
    @UiField Element javaVersion;
    @UiField Element osName;

    private final Store store;
    private boolean busy;
    private boolean passwordShown;

    LoginController(Store store) {
        this.store = store;
    }

    void initialize() {
        Ui.applyAppearance(window, store);
        window.setTitle("Hanji Studio — 로그인").setMenuBar(menuBar());
        javaVersion.setText(System.getProperty("java.version"));
        osName.setText(System.getProperty("os.name") + " " + System.getProperty("os.arch"));
        if (store.rememberedId != null) {
            username.setValue(store.rememberedId);
            password.focus();
        } else {
            username.focus();
        }
    }

    private MenuBar menuBar() {
        MenuBar bar = new MenuBar();
        if (Ui.isMac()) {
            bar.add(new Menu("Hanji Studio", MenuItem.role(MenuRole.HIDE, "Hanji Studio 가리기"),
                    MenuItem.separator(), MenuItem.role(MenuRole.QUIT, "Hanji Studio 종료")));
        } else {
            bar.add(new Menu("파일", MenuItem.role(MenuRole.QUIT, "종료")));
        }
        bar.add(Menu.standard(MenuRole.EDIT_MENU, "편집"));
        bar.add(new Menu("도움말", new MenuItem("데모 계정 입력", "CmdOrCtrl+D", this::fillDemo)));
        return bar;
    }

    @UiHandler(value = "loginForm", event = "submit")
    void submit() {
        if (busy) {
            return;
        }
        String id = username.getValue() == null ? "" : username.getValue().trim();
        String pw = password.getValue() == null ? "" : password.getValue();
        boolean ok = check(userField, userError, id.isEmpty() ? "아이디를 입력하세요" : null);
        ok &= check(passField, passError, pw.isEmpty() ? "비밀번호를 입력하세요" : pw.length() < 4 ? "4자 이상 입력하세요" : null);
        loginError.setVisible(false);
        if (!ok) {
            shake();
            return;
        }
        busy = true;
        loginBtn.addClass("loading").setEnabled(false);
        // Network/database work belongs on a worker thread; the future completes on the UI thread again.
        Hanji.async(() -> {
            Thread.sleep(650);
            return Store.authenticate(id, pw);
        }).thenAccept(user -> {
            busy = false;
            if (user == null) {
                loginBtn.removeClass("loading").setEnabled(true);
                loginErrorText.setText("아이디 또는 비밀번호가 올바르지 않습니다");
                loginError.setVisible(true);
                password.setValue("").focus();
                shake();
                return;
            }
            store.user = user;
            store.rememberedId = remember.isChecked() ? id : null;
            window.load("showcase/index.html", new MainController(store));
        });
    }

    @UiHandler("fillDemo")
    void fillDemo() {
        username.setValue("admin");
        password.setValue("hanji");
        check(userField, userError, null);
        check(passField, passError, null);
        loginBtn.focus();
    }

    @UiHandler("togglePassword")
    void togglePassword() {
        passwordShown = !passwordShown;
        password.setAttribute("type", passwordShown ? "text" : "password");
    }

    @UiHandler("forgot")
    void forgot() {
        window.alert("비밀번호 찾기", "데모 계정은 admin / hanji 입니다.\n실제 앱이라면 여기서 재설정 메일을 보냅니다.");
    }

    private static boolean check(Element field, Element message, String error) {
        field.toggleClass("invalid", error != null);
        message.setText(error == null ? "" : error);
        return error == null;
    }

    private void shake() {
        loginForm.eval("el.classList.remove('shake'); void el.offsetWidth; el.classList.add('shake');");
    }
}
