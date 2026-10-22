# Hanji (한지)

**HTML/CSS로 화면을 만들고 Java로 제어하는 데스크톱 UI 프레임워크.**
Swing·JavaFX처럼 `Application → Window → 컴포넌트/이벤트` 모델을 그대로 쓰되, 화면은 HTML로 그립니다.

- **설치된 브라우저가 필요 없습니다.** VS Code·Discord처럼 Electron(Chromium 엔진)을 앱이 직접 가지고 실행합니다. Electron은 처음 실행할 때 한 번 내려받아 캐시하거나(SHA-256 검증), 앱과 함께 배포합니다.
- **네이티브 창 기능**: 실행 중 크기·위치 변경, 최소화·최대화·전체 화면, 메뉴바(macOS 상단 메뉴 포함), 시스템 트레이, 닫기 확인.
- **JavaFX·Swing·AWT 의존성 없음**: 코어는 JDK 모듈 `java.base`, `jdk.httpserver`, `java.net.http`(Electron 다운로드용)만 사용하고, 외부 라이브러리를 쓰지 않습니다(`jdeps`로 확인).
- **Java 17+**: Java 21 이상에서는 백그라운드 작업에 가상 스레드를 사용합니다.
- **Windows · macOS · Linux** 지원. 같은 JAR가 모든 OS에서 동작하며, OS별로 필요한 것은 Electron 실행 파일뿐입니다.

## 지원 OS

| OS | 기본 창 | 대안(개발·경량) | 검증 |
|---|---|---|---|
| **Linux** (X11 / Wayland) | Electron | 설치된 Chrome·Edge·Chromium 앱 모드, 기본 브라우저 탭 | Ubuntu 24.04에서 실제 창 실행 + CI |
| **macOS** (Intel / Apple Silicon) | Electron | Chrome·Edge 앱 모드, Safari 탭 | GitHub Actions macOS 러너 CI |
| **Windows** 10/11 | Electron | Edge(기본 탑재)·Chrome 앱 모드 | GitHub Actions Windows 러너 CI |

CI(`.github/workflows/ci.yml`)는 세 OS에서 Java 17·21로 실제 Electron 창과 Chrome 창을 띄워 E2E 테스트를 실행합니다.

## 프로그래밍 모델 3가지

| 방식 | 대응 개념 | 사용 API |
|---|---|---|
| HTML 템플릿 + 컨트롤러 | JavaFX FXML + `@FXML` | `window.load("page.html", Controller::new)`, `@UiField`, `@UiHandler`, `@JsCallable` |
| Java 코드로 화면 조립 | Swing | `VBox`, `HBox`, `Button`, `TextField`, `TableView` … + `window.setContent(...)` |
| 상태 바인딩 | JavaFX Property | `SimpleProperty`, `ObservableList`, `Bindings`, `bindText/bindEnabled/...` |

세 방식은 섞어서 써도 됩니다. 예를 들어 HTML로 레이아웃을 잡고, `@UiField TableView<Person> table;`처럼 HTML 요소에 컴포넌트를 씌울 수 있습니다.

## 빠른 시작

### 1) HTML + 컨트롤러 (FXML 스타일)

`src/main/resources/web/index.html`
```html
<!DOCTYPE html>
<html lang="ko">
<head><meta charset="utf-8"><title>인사</title></head>
<body>
  <input id="name" placeholder="이름">
  <button id="greet">인사</button>
  <p id="message"></p>
</body>
</html>
```

```java
public class HelloApp extends HanjiApplication {

    public static class HelloController {
        @UiField Element name;      // id="name"
        @UiField Element message;   // id="message"

        void initialize() {                     // 페이지 로드 + 주입 완료 후 호출
            message.setText("이름을 입력하세요");
        }

        @UiHandler("greet")                     // id="greet" 의 click
        void greet() {
            message.setText("안녕하세요, " + name.getValue() + "님");   // 입력값은 왕복 없이 바로 읽힘
        }
    }

    @Override
    public void start(Window window) {
        window.setTitle("Hello").setSize(480, 320)
              .load("index.html", HelloController::new)
              .show();
    }

    public static void main(String[] args) { launch(args); }
}
```

### 2) Java 코드만으로 (Swing 스타일)

```java
Hanji.launch(window -> {
    TextField name = new TextField().setPlaceholder("이름");
    Label message = new Label();
    Button greet = new Button("인사", e -> message.setText("안녕하세요, " + name.getText()));
    greet.bindEnabled(name.textProperty().map(t -> !t.isBlank()));   // 입력이 있을 때만 활성화

    window.setTitle("Hello").setContent(new VBox(name, greet, message)).show();
});
```

### 3) JavaScript ↔ Java

```java
public class Controller {
    @JsCallable                                   // JS: await hanji.call('findUsers', '김')
    List<User> findUsers(String keyword) { ... }  // 인자와 반환값은 JSON으로 자동 변환(record·POJO 지원)

    @JsCallable(background = true)                // UI 스레드가 아닌 워커 스레드에서 실행
    Report heavyReport(ReportRequest req) { ... }
}
```
```js
const users = await hanji.call('findUsers', '김');
hanji.emit('saved', { id: 3 });                    // Java: window.onMessage("saved", Saved.class, s -> ...)
hanji.on('progress', p => bar.style.width = p.pct + '%');   // Java: window.publish("progress", Map.of("pct", 40))
```
Java에서 페이지 쪽 코드를 호출할 수도 있습니다.
```java
window.call("charts.update", data);                         // 전역 JS 함수 호출
window.eval("document.title").thenAccept(t -> ...);         // 비동기
String text = element.getText();                            // 동기 조회 (아래 "동기 조회" 참고)
```

## 네이티브 창 기능 (Electron 호스트)

```java
window.setSize(900, 600).setPosition(100, 80).setMinimumSize(640, 400);   // show() 전후 모두 가능
window.maximize();  window.minimize();  window.restore();  window.setFullScreen(true);
window.setAlwaysOnTop(true);  window.toFront();  window.center();

window.boundsProperty().addListener((o, old, b) -> saveLayout(b));   // 위치·크기 변경 감지
window.stateProperty();   window.focusedProperty();                  // 상태·포커스(바인딩 가능)

// 메뉴바: macOS에서는 상단 메뉴바가 되고, 앱 메뉴와 표준 편집 메뉴가 자동으로 붙어 Cmd+C/V/Q가 동작합니다
window.setMenuBar(new MenuBar(
        new Menu("파일",
                new MenuItem("새 파일", "CmdOrCtrl+N", this::newFile),
                MenuItem.checkbox("자동 저장", true, on -> autoSave = on),
                MenuItem.separator(),
                MenuItem.role(MenuRole.QUIT, "종료")),
        Menu.standard(MenuRole.EDIT_MENU, "편집")));
saveItem.setEnabled(false);   // 나중에 바꿔도 메뉴에 자동 반영

// 닫기 확인: 저장하지 않은 변경이 있으면 묻기
window.setOnCloseRequest(request -> {
    if (dirty) {
        request.cancel();
        window.confirm("저장하지 않고 닫을까요?").thenAccept(ok -> { if (ok) window.close(); });
    }
});

// 시스템 트레이
Tray.create().setTooltip("내 앱").setOnAction(window::toFront)
        .setMenu(new Menu(null, new MenuItem("열기", window::toFront), new MenuItem("종료", Hanji::exit)));
```
Electron이 아닌 호스트(개발용 Chrome 앱 모드 등)에서는 이 호출들을 경고 한 번만 남기고 무시하므로, 같은 코드가 그대로 동작합니다. `window.hasNativeControls()`, `tray.isSupported()`로 지원 여부를 확인할 수 있습니다.

## 핵심 API

### Window
| 메서드 | 설명 |
|---|---|
| `load(path, controller \| factory)` / `loadHtml(html)` / `setContent(node)` | 페이지 지정 |
| `show()` / `close()` / `onClosed(r)` / `onReady(r)` / `setOnCloseRequest(h)` | 생명주기 |
| `setSize` / `setPosition` / `setBounds` / `maximize` / `minimize` / `restore` / `setFullScreen` / `toFront` | 창 제어 |
| `setMenuBar(menuBar)` / `boundsProperty()` / `stateProperty()` / `focusedProperty()` | 메뉴·상태 |
| `byId(id)` / `body()` / `document()` / `global()` / `query(selector)` | 요소 조회 |
| `alert` / `confirm` / `prompt` | HTML `<dialog>` 기반 비동기 대화상자(`CompletableFuture`) |
| `eval` / `evalSync` / `call` / `publish` / `onMessage` / `expose` | JS 연동 |

### Element (DOM 프록시)
`setText`, `setHtml`, `setValue/getValue`, `setChecked/isChecked`, `setAttribute`, `addClass/removeClass/toggleClass`,
`setStyle`, `setVisible`, `setEnabled`, `append/prepend/insertBefore/remove/clear/dispose`, `focus/click/invoke`,
`on(type, [EventOptions], handler)`, `bindText/bindValue/bindChecked/bindVisible/bindEnabled/bindClass/bindAttribute`,
동기 조회 `getText/getHtml/getAttribute/getProperty/evalSync`.

- `Element.create("div")`로 만든 요소는 창에 붙기 전까지 명령을 기록해 두었다가, 붙는 순간 순서대로 재생합니다. 그래서 Swing처럼 트리를 먼저 조립할 수 있습니다.
- 페이지의 `input/select/textarea` 값(`id`가 있는 요소)은 Java로 자동 미러링되므로 `getValue()`·`isChecked()`는 왕복 없이 바로 읽힙니다.
- Java가 참조하고 있는 요소는 부모의 `clear()`나 `setHtml()` 뒤에도 페이지에 보존되어 다시 `append`할 수 있습니다. Java 쪽 참조가 사라지면(GC) 페이지에서도 정리됩니다.
- `EventOptions`: `preventDefault()`, `stopPropagation()`, `debounce(ms)`, `delegate(".row")`, `keys("Enter")`.

### 동기 조회
`getText()`, `getAttribute()`, `evalSync()` 같은 동기 조회는 페이지까지 다녀옵니다. UI 스레드에서 호출하면 응답을 기다리는 동안 다른 UI 작업(이벤트, 응답 수신)을 계속 처리하는 **중첩 이벤트 루프**로 대기하므로 교착 상태가 생기지 않습니다(Swing 모달 대화상자와 같은 원리). 다른 스레드에서 호출하면 그 스레드만 기다립니다. 시간 제한은 `HanjiConfig.setSyncTimeoutMillis`(기본 10초)입니다.

### 어노테이션
| 어노테이션 | 대상 | 설명 |
|---|---|---|
| `@UiField("id")` | 필드 | `Element`, 컴포넌트(`TextField`, `ListView<T>` …), `Window` 주입. id를 생략하면 필드 이름 사용 |
| `@UiHandler(value = {"id"}, event = "click", debounce, delegate, keys, preventDefault)` | 메서드 `()` 또는 `(Event)` | 이벤트 핸들러. `#document`, `#window`, `#body`도 지정 가능 |
| `@JsCallable(value = "name", background = false)` | 메서드 | `hanji.call(name, ...)`로 호출 가능 |

컨트롤러에 `initialize()` 또는 `initialize(Window)` 메서드가 있으면 주입 후 호출합니다(JavaFX와 같은 규칙).

### 컴포넌트 (`io.hanji.ui`)
`Label`, `Button`, `TextField`, `PasswordField`, `TextArea`, `CheckBox`, `ComboBox<T>`, `ListView<T>`, `TableView<T>`(헤더 클릭 정렬, 행 선택), `VBox`, `HBox`, `Panel`.
기본 스타일은 `hj-*` 클래스로만 지정되어 있어 페이지 CSS와 충돌하지 않고, `--hj-accent` 같은 CSS 변수로 테마를 바꿀 수 있습니다.

### 스레드 모델
- 모든 UI 코드는 **단일 UI 스레드(`Hanji-UI`)**에서 실행됩니다(Swing EDT, JavaFX Application Thread와 같은 개념). 다른 스레드에서 UI를 건드리면 `IllegalStateException`이 발생합니다.
- 다른 스레드에서 UI를 갱신할 때는 `Hanji.runLater(...)`를 사용합니다.
- `Hanji.async(() -> 느린작업()).thenAccept(r -> ui갱신(r))`처럼 쓰면 작업은 워커 스레드에서, 후속 처리는 UI 스레드에서 실행됩니다.
- 한 번의 UI 작업에서 발생한 DOM 변경은 작업이 끝날 때 **한 묶음으로** 페이지에 전송됩니다.

## 동작 구조

```
 Java 프로세스                                                 Electron 프로세스 (앱당 1개, 창 여러 개)
 ─────────────────────────────                                 ──────────────────────────────────────────
 Window / Element / 메뉴 / 트레이                                main.js : 네이티브 창·메뉴·트레이
   │  창 제어 명령·이벤트  ◀──── TCP 127.0.0.1 (토큰 인증) ────▶     │
   │                                                               │ IPC (preload.js, contextIsolation)
 내장 HTTP 서버 (127.0.0.1)                                       페이지 (hanji.js)
   ├ /{token}/page.html, css, js  ◀──── 페이지·자원 로드 ────────    │
   └ /{token}/__hanji/events·send ◀──── main.js가 대신 연결 ──── 페이지 ↔ main.js 는 IPC
```
- 창마다 추측할 수 없는 토큰이 URL에 들어가고, 서버와 제어 소켓은 루프백(127.0.0.1)에만 바인딩됩니다.
- 페이지 통신을 Electron 메인 프로세스가 대신 처리하므로, 창이 몇 개든 브라우저의 "호스트당 연결 6개" 제한을 받지 않습니다.
- 앱 밖 URL로 이동하거나 새 창을 열면 시스템 기본 브라우저로 엽니다.
- Java 프로세스가 끝나면 제어 소켓이 끊기고 Electron도 함께 종료되므로 고아 프로세스가 남지 않습니다.
- HTML 응답에는 클라이언트 런타임(`hanji.js`, `hanji.css`)이 `<head>` 맨 앞에 자동 삽입됩니다.

## 창 호스트

| `hanji.host` | 동작 | 자동 선택 순위 |
|---|---|---|
| `electron` | 내장 Electron으로 창 표시. 네이티브 창 기능 전부 지원 | 1 (디스플레이가 있으면 기본) |
| `app` | 설치된 Chrome·Edge·Chromium·Brave를 `--app=URL`로 실행. 다운로드 없이 빠르게 확인할 때 | 2 |
| `browser` | 기본 브라우저 탭으로 열기 | 3 |
| `external` | 창을 열지 않고 URL만 출력(서버·CI·원격 디버깅용). 디스플레이가 없으면 자동 선택 | 4 |

Electron을 받을 수 없으면(오프라인 등) 자동 모드는 다음 순위 호스트로 넘어갑니다.

## Electron 런타임과 배포

Electron(기본 44.9.3)을 찾는 순서는 다음과 같습니다.
1. `-Dhanji.electron=경로`(또는 환경변수 `HANJI_ELECTRON`): 실행 파일이나 압축을 푼 Electron 폴더
2. 캐시: `~/.cache/hanji`(Linux), `~/Library/Caches/hanji`(macOS), `%LOCALAPPDATA%\hanji\cache`(Windows)
3. 다운로드: GitHub 공식 릴리스에서 받아 SHA-256을 검증한 뒤 캐시에 설치(`-Dhanji.electronDownload=false`로 끔)

앱과 함께 배포하려면 미리 설치해 두고 경로를 지정합니다.
```bash
java -cp hanji-core.jar io.hanji.electron.ElectronRuntime install --dir build/electron
# 실행 시: -Dhanji.electron=build/electron/44.9.3/<platform>  (예: linux-x64, darwin-arm64, win32-x64)
```
플랫폼별 크기(압축 기준)는 macOS 약 115MB, Linux 약 118MB, Windows 약 150MB입니다.

## 설정

`-Dhanji.xxx=` 시스템 속성 또는 `HanjiApplication.configure(HanjiConfig)`로 지정합니다.

| 속성 | 기본값 | 설명 |
|---|---|---|
| `hanji.host` | `auto` | `electron` / `app` / `browser` / `external` |
| `hanji.webRoot` | `web` | 페이지를 찾는 클래스패스 폴더 |
| `hanji.appName` | `Hanji` | 앱 이름(Electron 사용자 데이터 폴더, macOS 메뉴) |
| `hanji.electron` (환경변수 `HANJI_ELECTRON`) | 자동 | Electron 실행 파일 또는 폴더 |
| `hanji.electronVersion` | `44.9.3` | 내려받을 Electron 버전 |
| `hanji.electronDownload` | `true` | Electron이 없을 때 내려받을지 여부 |
| `hanji.electronArgs` | | Electron/Chromium 추가 인자(공백 구분) |
| `hanji.cacheDir` (환경변수 `HANJI_CACHE`) | OS 캐시 폴더 | 내려받은 런타임 위치 |
| `hanji.browser` (환경변수 `HANJI_BROWSER`) | 자동 탐색 | `app` 호스트가 쓸 브라우저 |
| `hanji.browserArgs` | | 브라우저 추가 인자(공백 구분) |
| `hanji.dev` | `false` | 개발 모드: `src/main/resources`에서 직접 읽어 빌드 없이 F5로 반영, F12 개발자 도구 |
| `hanji.devResources` | `src/main/resources` | 개발 모드의 소스 폴더 |
| `hanji.port` | `0`(임의) | 서버 포트. 고정하면 `localStorage`가 실행 간에 유지됨 |
| `hanji.closeGrace` | `2500` | 브라우저 기반 호스트에서 연결이 끊긴 뒤 창이 닫힌 것으로 판단하기까지의 시간(ms) |
| `hanji.debug` | `false` | 명령 배치, Electron 로그 등 상세 출력 |

개발 모드에서 `load(path, Controller::new)`처럼 팩토리를 넘기면, F5로 새로고침할 때마다 컨트롤러가 새로 만들어집니다. 개발 모드가 아닐 때는 F5·Ctrl+R이 막혀 있습니다.

## 프로젝트 구성과 실행

```
hanji/
├─ hanji-core/       프레임워크 (외부 의존성 없음)
│  ├─ src/main/java/io/hanji/            Hanji, HanjiApplication, Window, Element, Tray, 어노테이션, 브리지
│  ├─ src/main/java/io/hanji/ui/         컴포넌트
│  ├─ src/main/java/io/hanji/menu/       MenuBar, Menu, MenuItem, MenuRole
│  ├─ src/main/java/io/hanji/property/   Property, ObservableList, Bindings
│  ├─ src/main/java/io/hanji/json/       JSON 파서/매퍼
│  ├─ src/main/java/io/hanji/electron/   Electron 호스트와 런타임 설치기
│  ├─ src/main/java/io/hanji/spi|host/   창 호스트 SPI와 브라우저 기반 호스트
│  └─ src/main/resources/io/hanji/       client/(hanji.js, hanji.css), electron/app/(main.js, preload.js)
└─ hanji-examples/   todo(HTML+컨트롤러, 메뉴바), components(순수 Java, 닫기 확인), monitor(JS 차트, 트레이)
```

```bash
mvn install                                              # 빌드 + 테스트
mvn -pl hanji-examples exec:java -Dexample=todo          # 또는 components, monitor
mvn -pl hanji-examples exec:java -Dexample=todo -Dhanji.host=app   # 설치된 Chrome/Edge로 빠르게
```

다른 프로젝트에서 사용할 때:
```xml
<dependency>
    <groupId>io.hanji</groupId>
    <artifactId>hanji-core</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```
JPMS(module-info)를 쓰는 경우 `requires io.hanji;`를 추가하고, 컨트롤러가 있는 패키지는 리플렉션을 위해 `opens com.example.ui to io.hanji;`로 열어야 합니다.

## 테스트

| 테스트 | 내용 |
|---|---|
| 단위 테스트 | JSON, 매퍼, Property/ObservableList, Element 명령 버퍼링, HTML 주입, 창 닫힘 판정, Electron 런타임 경로 해석 |
| `BrowserE2ETest` | 헤드리스 Chromium을 DevTools 프로토콜로 조작: 컨트롤러 주입, 이벤트, 입력값 미러링, 폼→record, JS↔Java 호출, 다이얼로그, 새로고침, 동기 조회, `clear()` 후 재사용, 창 닫힘 감지 |
| `AppHostE2ETest` | 설치된 Chrome/Edge 앱 모드 창 실행 → 조작 → 사용자가 닫음 → 앱 종료 |
| `ElectronHostE2ETest` | Electron 창: IPC 통신, 실행 중 크기·위치, 최대화·최소화, 메뉴(체크·비활성), 트레이, 동시 창 8개, 닫기 확인 취소/수락, 마지막 창 닫으면 종료 |

```bash
mvn install                                   # 브라우저·디스플레이가 없으면 E2E는 건너뜀
HANJI_REQUIRE_E2E=1 mvn install               # 건너뛰지 않고 실패 처리(CI)
xvfb-run -a bash -c 'openbox & mvn install'   # 화면 없는 Linux: 가상 디스플레이 + 창 관리자
```
CI는 테스트 클래스별 실행·건너뜀 개수와 실패 메시지를 실행 화면의 주석(annotation)으로 남깁니다.

## 현재 제약 사항

- **네이티브 파일 열기·저장 대화상자**는 아직 없습니다. 지금은 HTML `<input type="file">`을 쓰거나 Java에서 경로를 받아야 합니다.
- **macOS 배포 시 이름·아이콘**: 내려받은 Electron을 그대로 쓰면 Dock과 메뉴바에 "Electron" 이름과 아이콘이 보입니다. 앱 이름으로 바꾸려면 `Electron.app`의 이름·`Info.plist`·아이콘을 바꾸고 다시 서명해야 하며, 이 패키징 단계는 아직 자동화되지 않았습니다. 일반 배포에는 Apple 코드 서명·공증이 필요합니다(Java 앱도 같음).
- **Linux 샌드박스**: Ubuntu 24.04처럼 사용자 네임스페이스를 제한한 배포판에서는 Electron 샌드박스를 쓸 수 없어 경고와 함께 샌드박스 없이 실행합니다. 정식 배포 패키지는 `chrome-sandbox`를 setuid root로 설치하거나 AppArmor 프로필을 함께 설치해야 합니다.
- **Linux 트레이**: 상태 표시줄을 지원하지 않는 데스크톱(확장 없는 GNOME 등)에서는 트레이 아이콘이 보이지 않습니다.
- **용량**: Electron 때문에 앱 배포 크기가 플랫폼별 115~150MB(압축) 늘어납니다. 용량이 중요하면 `app` 호스트(설치된 Chrome/Edge 사용)를 선택할 수 있습니다.
- **`browser` 호스트**는 일반 브라우저 탭이라 창 제어가 안 되고, 탭을 6개 넘게 열면 브라우저의 호스트당 연결 제한에 걸릴 수 있습니다(개발·확인용).
- **`localStorage`**: 기본 포트가 실행마다 바뀌어 저장 내용이 유지되지 않습니다. 유지하려면 `hanji.port`를 고정하세요.
