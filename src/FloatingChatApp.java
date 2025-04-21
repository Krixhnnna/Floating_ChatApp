import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.*;
import com.sun.jna.platform.win32.*;
import com.sun.jna.platform.win32.WinUser.*;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.RECT;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.ptr.IntByReference;

class ResponseFormatter {
    public static String stripCodeBlockMarkers(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        
        // Remove opening markdown code block markers like ```java, ```c, etc.
        Pattern openingPattern = Pattern.compile("^```[a-zA-Z0-9]*\\s*\\n", Pattern.MULTILINE);
        Matcher openingMatcher = openingPattern.matcher(text);
        String result = openingMatcher.replaceAll("");
        
        // Remove closing markdown code block markers
        result = result.replaceAll("```\\s*$", "").trim();
        
        return result;
    }
}

interface ExtendedUser32 extends User32 {
    ExtendedUser32 INSTANCE = Native.load("user32", ExtendedUser32.class);
    
    boolean GetGUIThreadInfo(int idThread, WinUser.GUITHREADINFO lpgui);
    
    LRESULT SendMessage(HWND hWnd, int Msg, WPARAM wParam, LPARAM lParam);
    
    boolean AttachThreadInput(int idAttach, int idAttachTo, boolean fAttach);
    
    int GetWindowTextLength(HWND hWnd);
    int GetWindowText(HWND hWnd, char[] lpString, int nMaxCount);
    
    boolean GetWindowRect(HWND hWnd, RECT rect);
    
    static final int WM_GETTEXT = 0x000D;
    static final int WM_GETTEXTLENGTH = 0x000E;
    static final int WM_COPY = 0x0301;
    static final int EM_GETSEL = 0x00B0;
    static final int EM_SETSEL = 0x00B1;
}

public class FloatingChatApp extends Application {
    private static Stage primaryStage;
    private static boolean isVisible = true;
    private static boolean isMinimized = false;
    private static double lastX, lastY;
    private static final String GEMINI_API_KEY = "AIzaSyB1LON40MDHU2MxLNJTEmmscIhQLZWAeW8"; // Replace with your actual API key

    // Define constants for hotkeys
    private static final int VK_H = 0x48;
    private static final int VK_B = 0x42;
    private static final int VK_M = 0x4D;
    private static final int VK_CONTROL = WinUser.VK_CONTROL;
    private static final int VK_ALT = WinUser.VK_MENU; // ALT key in Windows is VK_MENU
    
    // Define user32 constants
    private static final int KEYEVENTF_KEYUP = 0x0002;

    @Override
    public void start(Stage stage) {
        primaryStage = stage;
        primaryStage.initStyle(StageStyle.UTILITY);
        primaryStage.setAlwaysOnTop(true);
        primaryStage.setOpacity(0.8);
        primaryStage.setTitle("Floating Chat");

        VBox root = new VBox(10);
        root.setStyle("-fx-background-color: #222; -fx-padding: 10;");

        TextArea answerArea = new TextArea();
        answerArea.setEditable(false);
        answerArea.setStyle("-fx-control-inner-background: #333; -fx-text-fill: #fff;");

        TextField questionField = new TextField();
        questionField.setPromptText("Your Question (Press Enter to Send)");
        questionField.setStyle("-fx-background-color: #444; -fx-text-fill: #fff;");

        Button copyButton = new Button("Copy");
        copyButton.setStyle("-fx-background-color: #555; -fx-text-fill: white;");
        copyButton.setOnAction(_ -> {
            String answer = answerArea.getText();
            if (!answer.isEmpty()) {
                Clipboard clipboard = Clipboard.getSystemClipboard();
                ClipboardContent content = new ClipboardContent();
                content.putString(answer);
                clipboard.setContent(content);
            }
        });

        questionField.setOnAction(_ -> {
            String question = questionField.getText().trim();
            if (question.isEmpty()) return;

            answerArea.setText("Fetching response...");
            new Thread(() -> {
                String response = fetchGeminiResponse(question);
                Platform.runLater(() -> {
                    answerArea.setText(response);
                    if (question.matches(".*\\b(code|program|script)\\b.*")) {
                        if (!root.getChildren().contains(copyButton)) root.getChildren().add(copyButton);
                    } else if (question.matches(".*\\b(option\\s*[1-4])\\b.*")) {
                        root.getChildren().remove(copyButton);
                    } else {
                        root.getChildren().remove(copyButton);
                    }
                });
            }).start();
        });

        root.getChildren().addAll(answerArea, questionField);
        primaryStage.setScene(new Scene(root, 400, 300));
        primaryStage.show();

        new Thread(FloatingChatApp::setupGlobalHotkeys).start();
    }

    private static void setupGlobalHotkeys() {
        User32 user32 = User32.INSTANCE;
        WinDef.HMODULE hMod = Kernel32.INSTANCE.GetModuleHandle(null);
        LowLevelKeyboardProc keyboardHook = (nCode, wParam, info) -> {
            if (nCode >= 0 && wParam.intValue() == WinUser.WM_KEYDOWN) {
                // Check for Ctrl+B
                if (info.vkCode == VK_B && (User32.INSTANCE.GetAsyncKeyState(VK_CONTROL) < 0)) {
                    Platform.runLater(FloatingChatApp::toggleVisibility);
                } 
                // Check for Ctrl+M
                else if (info.vkCode == VK_M && (User32.INSTANCE.GetAsyncKeyState(VK_CONTROL) < 0)) {
                    Platform.runLater(FloatingChatApp::fetchSelectedText);
                }
                // Check for Ctrl+Alt+H
                else if (info.vkCode == VK_H && 
                        (User32.INSTANCE.GetAsyncKeyState(VK_CONTROL) < 0) && 
                        (User32.INSTANCE.GetAsyncKeyState(VK_ALT) < 0)) {
                    captureProtectedText();
                }
            }
            return User32.INSTANCE.CallNextHookEx(null, nCode, wParam, new WinDef.LPARAM(Pointer.nativeValue(info.getPointer())));
        };

        WinUser.HHOOK hhk = user32.SetWindowsHookEx(WinUser.WH_KEYBOARD_LL, keyboardHook, hMod, 0);
        WinUser.MSG msg = new WinUser.MSG();
        while (user32.GetMessage(msg, null, 0, 0) > 0) {
            user32.TranslateMessage(msg);
            user32.DispatchMessage(msg);
        }
        user32.UnhookWindowsHookEx(hhk);
    }
    
    private static void captureProtectedText() {
        try {
            ExtendedUser32 extUser32 = ExtendedUser32.INSTANCE;
            User32 user32 = User32.INSTANCE;
            Kernel32 kernel32 = Kernel32.INSTANCE;
            
            // Get foreground window
            HWND foregroundWindow = extUser32.GetForegroundWindow();
            if (foregroundWindow == null) return;
            
            // Get thread info
            IntByReference foregroundThreadId = new IntByReference();
            int foregroundProcessId = extUser32.GetWindowThreadProcessId(foregroundWindow, foregroundThreadId);
            int currentThreadId = kernel32.GetCurrentThreadId();
            
            // Get GUI thread info to find the focused control
            WinUser.GUITHREADINFO guiInfo = new WinUser.GUITHREADINFO();
            boolean success = extUser32.GetGUIThreadInfo(foregroundThreadId.getValue(), guiInfo);
            
            if (!success || guiInfo.hwndFocus == null) {
                // Try using the foreground window if focused control not found
                guiInfo.hwndFocus = foregroundWindow;
            }
            
            // Attach to the thread to simulate user input
            extUser32.AttachThreadInput(currentThreadId, foregroundThreadId.getValue(), true);
            
            // First method: Try to select all text and simulate Ctrl+C
            // Simulate CTRL+A and CTRL+C using SendInput
            INPUT[] inputs = (INPUT[]) new INPUT().toArray(6);

            // CTRL down
            inputs[0].type = new WinDef.DWORD(INPUT.INPUT_KEYBOARD);
            inputs[0].input.setType("ki");
            inputs[0].input.ki.wVk = new WinDef.WORD(0x11); // VK_CONTROL

            // A down
            inputs[1].type = new WinDef.DWORD(INPUT.INPUT_KEYBOARD);
            inputs[1].input.setType("ki");
            inputs[1].input.ki.wVk = new WinDef.WORD(0x41); // 'A'

            // A up
            inputs[2].type = new WinDef.DWORD(INPUT.INPUT_KEYBOARD);
            inputs[2].input.setType("ki");
            inputs[2].input.ki.wVk = new WinDef.WORD(0x41); // 'A'
            inputs[2].input.ki.dwFlags = new WinDef.DWORD(KEYEVENTF_KEYUP);

            // C down
            inputs[3].type = new WinDef.DWORD(INPUT.INPUT_KEYBOARD);
            inputs[3].input.setType("ki");
            inputs[3].input.ki.wVk = new WinDef.WORD(0x43); // 'C'

            // C up
            inputs[4].type = new WinDef.DWORD(INPUT.INPUT_KEYBOARD);
            inputs[4].input.setType("ki");
            inputs[4].input.ki.wVk = new WinDef.WORD(0x43); // 'C'
            inputs[4].input.ki.dwFlags = new WinDef.DWORD(KEYEVENTF_KEYUP);

            // CTRL up
            inputs[5].type = new WinDef.DWORD(INPUT.INPUT_KEYBOARD);
            inputs[5].input.setType("ki");
            inputs[5].input.ki.wVk = new WinDef.WORD(0x11); // VK_CONTROL
            inputs[5].input.ki.dwFlags = new WinDef.DWORD(KEYEVENTF_KEYUP);

            User32.INSTANCE.SendInput(new WinDef.DWORD(inputs.length), inputs, inputs[0].size());
            
            // Second method: Try sending WM_COPY message directly
            extUser32.SendMessage(guiInfo.hwndFocus, ExtendedUser32.WM_COPY, new WinDef.WPARAM(0), new WinDef.LPARAM(0));
            
            // Third method: For edit controls, get selection and text
            IntByReference start = new IntByReference(0);
            IntByReference end = new IntByReference(0);
            
            int selResult = extUser32.SendMessage(guiInfo.hwndFocus, ExtendedUser32.EM_GETSEL, new WinDef.WPARAM(0), new WinDef.LPARAM(0)).intValue();
            if (selResult == 0) {
                // No selection, try selecting all text
                int textLength = extUser32.SendMessage(guiInfo.hwndFocus, ExtendedUser32.WM_GETTEXTLENGTH, new WinDef.WPARAM(0), new WinDef.LPARAM(0)).intValue();
                if (textLength > 0) {
                    extUser32.SendMessage(guiInfo.hwndFocus, ExtendedUser32.EM_SETSEL, new WinDef.WPARAM(0), new WinDef.LPARAM(textLength));
                    extUser32.SendMessage(guiInfo.hwndFocus, ExtendedUser32.WM_COPY, new WinDef.WPARAM(0), new WinDef.LPARAM(0));
                }
            } else {
                // Selection exists, copy it
                extUser32.SendMessage(guiInfo.hwndFocus, ExtendedUser32.WM_COPY, new WinDef.WPARAM(0), new WinDef.LPARAM(0));
            }
            
            // Detach from the thread
            extUser32.AttachThreadInput(currentThreadId, foregroundThreadId.getValue(), false);
            
            // Small delay to ensure copy operation completes
            Thread.sleep(100);
            
            // Now paste the result into our app
            Platform.runLater(() -> {
                Clipboard clipboard = Clipboard.getSystemClipboard();
                if (clipboard.hasString()) {
                    String capturedText = clipboard.getString();
                    ((TextArea) ((VBox) primaryStage.getScene().getRoot()).getChildren().get(0)).setText(
                            "Captured Text:\n\n" + capturedText);
                } else {
                    ((TextArea) ((VBox) primaryStage.getScene().getRoot()).getChildren().get(0)).setText(
                            "Failed to capture text. The application might be using enhanced security.");
                }
            });
            
        } catch (Exception e) {
            Platform.runLater(() -> {
                ((TextArea) ((VBox) primaryStage.getScene().getRoot()).getChildren().get(0)).setText(
                        "Error capturing text: " + e.getMessage());
            });
        }
    }

    private static void toggleVisibility() {
        if (isVisible) {
            lastX = primaryStage.getX();
            lastY = primaryStage.getY();
            primaryStage.setX(10);
            primaryStage.setY(primaryStage.getY() + primaryStage.getHeight() - 50);
            primaryStage.setWidth(150);
            primaryStage.setHeight(50);
            isMinimized = true;
        } else {
            if (isMinimized) {
                primaryStage.setX(lastX);
                primaryStage.setY(lastY);
                primaryStage.setWidth(400);
                primaryStage.setHeight(300);
            }
            primaryStage.toFront();
            primaryStage.requestFocus();
        }
        isVisible = !isVisible;
    }

    private static void fetchSelectedText() {
        Clipboard clipboard = Clipboard.getSystemClipboard();
        if (clipboard.hasString()) {
            String selectedText = clipboard.getString().trim();
            if (!selectedText.isEmpty()) {
                new Thread(() -> {
                    String response = fetchGeminiResponse(selectedText);
                    Platform.runLater(() -> {
                        ((TextArea) ((VBox) primaryStage.getScene().getRoot()).getChildren().get(0)).setText(response);
                    });
                }).start();
            }
        }
    }
    
    private static String fetchGeminiResponse(String question) {
        try {
            URL url = URI.create("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=" + GEMINI_API_KEY).toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setDoOutput(true);

            JSONObject requestBody = new JSONObject();
            requestBody.put("contents", new JSONArray()
                .put(new JSONObject()
                    .put("role", "user")
                    .put("parts", new JSONArray()
                        .put(new JSONObject().put("text", "Act like you are a professional coder and you know all coding languages. "
                            + "If user asks any question then you only give code, no extra texts only pure code. "
                            + "If coding language is not mentioned, return only C language code. If a language is mentioned, return it in that language. "
                            + "For MCQs, answer only with 'Option 1', 'Option 2', etc."))))
                .put(new JSONObject()
                    .put("role", "user")
                    .put("parts", new JSONArray()
                        .put(new JSONObject().put("text", question)))));

            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = requestBody.toString().getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            int responseCode = conn.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) {
                        response.append(line);
                    }
                    JSONObject jsonResponse = new JSONObject(response.toString());
                    return ResponseFormatter.stripCodeBlockMarkers(jsonResponse.getJSONArray("candidates")
                        .getJSONObject(0)
                        .getJSONObject("content")
                        .getJSONArray("parts")
                        .getJSONObject(0)
                        .getString("text"));
                }
            } else {
                return "Error: Gemini API responded with code " + responseCode;
            }
        } catch (Exception e) {
            return "Error fetching response: " + e.getMessage();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}