package com.jarvis.assistant.neural;

import java.nio.ByteBuffer;
import org.tensorflow.lite.Interpreter;

/** Java-only public boundary for the retained LiteRT Interpreter API.
 * Owns one model interpreter, never audio hardware, Android services or network access.
 * Keep SDK types private so app Kotlin/Room metadata stays independent of optional SDK APIs.
 */
public final class WakeInterpreter implements AutoCloseable {
    private final Interpreter interpreter;

    public WakeInterpreter(ByteBuffer model) {
        interpreter = new Interpreter(model, new Interpreter.Options().setNumThreads(1));
    }

    public void resizeInput(int index, int[] dimensions) {
        interpreter.resizeInput(index, dimensions);
    }

    public void allocateTensors() {
        interpreter.allocateTensors();
    }

    public int[] inputShape(int index) {
        return interpreter.getInputTensor(index).shape();
    }

    public int outputElements(int index) {
        return interpreter.getOutputTensor(index).numElements();
    }

    public void run(ByteBuffer input, ByteBuffer output) {
        interpreter.run(input, output);
    }

    @Override public void close() {
        interpreter.close();
    }
}
