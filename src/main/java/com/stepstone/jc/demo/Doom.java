package com.stepstone.jc.demo;

import com.dylibso.chicory.runtime.HostFunction;
import com.dylibso.chicory.runtime.ImportMemory;
import com.dylibso.chicory.runtime.ImportValues;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.runtime.ByteBufferMemory;
import com.dylibso.chicory.runtime.WasmFunctionHandle;
import com.dylibso.chicory.wasm.Parser;
import com.dylibso.chicory.wasm.types.FunctionType;
import com.dylibso.chicory.wasm.types.MemoryLimits;
import com.dylibso.chicory.wasm.types.ValType;

import java.awt.EventQueue;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static java.awt.image.BufferedImage.TYPE_4BYTE_ABGR;

public class Doom {
    static int doomScreenWidth = 640;
    static int doomScreenHeight = 400;
    static String JS_MODULE_NAME = "js";
    private final ScheduledExecutorService scheduledExecutorService = Executors.newScheduledThreadPool(1);
    private final GameWindow gameWindow = new GameWindow();

    public static void main(String[] args) throws Exception {
       new Doom().runGame();
    }

    void runGame() throws IOException {
        EventQueue.invokeLater(() -> gameWindow.setVisible(true));

        // load WASM module
        var module = Parser.parse(Doom.class.getResourceAsStream("/doom.wasm"));

        //        import function js_js_milliseconds_since_start():int;
        //        import function js_js_console_log(a:int, b:int);
        //        import function js_js_draw_screen(a:int);
        //        import function js_js_stdout(a:int, b:int);
        //        import function js_js_stderr(a:int, b:int);
        var imports = ImportValues.builder()
                .addFunction(
                    new HostFunction[]{
                            new HostFunction(
                                    JS_MODULE_NAME,
                                    "js_milliseconds_since_start",
                                    FunctionType.of(List.of(), List.of(ValType.I32)),
                                    jsMillisecondsSinceStart()),
                            new HostFunction(
                                    JS_MODULE_NAME,
                                    "js_console_log",
                                    FunctionType.of(List.of(ValType.I32, ValType.I32), List.of()),
                                    jsConsoleLog()),
                            new HostFunction(
                                    JS_MODULE_NAME,
                                    "js_stdout",
                                    FunctionType.of(List.of(ValType.I32, ValType.I32), List.of()),
                                    jsStdout()),
                            new HostFunction(
                                    JS_MODULE_NAME,
                                    "js_stderr",
                                    FunctionType.of(List.of(ValType.I32, ValType.I32), List.of()),
                                    jsStderr()),
                            new HostFunction(
                                    JS_MODULE_NAME,
                                    "js_draw_screen",
                                    FunctionType.of(List.of(ValType.I32), List.of()),
                                    jsDrawScreen()),
                    })
                .addMemory(
                        new ImportMemory("env", "memory", new ByteBufferMemory(new MemoryLimits(108, 1000)))
                )
                .build();

        var instance = Instance.builder(module).withImportValues(imports).build();

        var addBrowserEvent = instance.export("add_browser_event");
        var doomLoopStep = instance.export("doom_loop_step");
        var main = instance.export("main");

        // run main() with doommy argc,argv pointers to set up some variables
        main.apply(0, 0);

        // schedule main game loop
        scheduledExecutorService.scheduleAtFixedRate(() -> {
            gameWindow.drainKeyEvents(event -> addBrowserEvent.apply(event[0], event[1]));
            doomLoopStep.apply();
        }, 0, 10, TimeUnit.MILLISECONDS);
    }

    private final long start = System.currentTimeMillis();

    /**
     * Used by game to track flow of time, to trigger events
     *
     * @return milliseconds from start of game
     */
    private WasmFunctionHandle jsMillisecondsSinceStart() {
        return (Instance instance, long ... args) -> new long[] {System.currentTimeMillis() - start };
    }
    private WasmFunctionHandle jsConsoleLog() {
        return (Instance instance, long ... args) -> {
            var offset = (int) args[0];
            var size = (int) args[1];

            System.out.println(instance.memory().readString(offset, size));

            return null;
        };
    }
    private WasmFunctionHandle jsStdout() {
        return (Instance instance, long ... args) -> {
            var offset = (int) args[0];
            var size = (int) args[1];

            System.out.print(instance.memory().readString(offset, size));

            return null;
        };
    }
    private WasmFunctionHandle jsStderr() {
        return (Instance instance, long ... args) -> {
            var offset = (int) args[0];
            var size = (int) args[1];

            System.err.print(instance.memory().readString(offset, size));

            return null;
        };
    }

    /**
     * Called when game draws to screen.
     * Fortunately doom screen buffer can be copied directly into {@link BufferedImage} buffer
     *
     */
    private WasmFunctionHandle jsDrawScreen() {
        return (Instance instance, long ... args) -> {
            var ptr = (int) args[0];

            int max = Doom.doomScreenWidth * Doom.doomScreenHeight * 4;
            int[] screenData = new int[max];
            for (int i = 0; i < max; i++) {
                byte pixelComponent = instance.memory().read(i + ptr);
                screenData[i] = pixelComponent;
            }
            BufferedImage bufferedImage = new BufferedImage(Doom.doomScreenWidth, Doom.doomScreenHeight, TYPE_4BYTE_ABGR);
            bufferedImage.getRaster().setPixels(0, 0, Doom.doomScreenWidth, Doom.doomScreenHeight, screenData);
            gameWindow.drawImage(bufferedImage);

            return null;
        };
    }

}
