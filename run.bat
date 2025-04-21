@echo off
echo Compiling...
javac --module-path "javafx-sdk/lib" --add-modules javafx.controls,javafx.fxml --add-exports javafx.graphics/com.sun.javafx.sg.prism=ALL-UNNAMED -cp "lib/*" -d out src/FloatingChatApp.java
if %ERRORLEVEL% NEQ 0 (
    echo Compilation failed!
    exit /b %ERRORLEVEL%
)
echo Running...
java --module-path "javafx-sdk/lib" --add-modules javafx.controls,javafx.fxml --add-exports javafx.graphics/com.sun.javafx.sg.prism=ALL-UNNAMED -cp "out;lib/*" FloatingChatApp