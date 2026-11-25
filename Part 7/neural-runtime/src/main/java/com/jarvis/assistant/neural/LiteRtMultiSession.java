package com.jarvis.assistant.neural;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.Tensor;

/**
 * Isolates LiteRT multi-input / multi-output interpreter operations inside `:neural-runtime`
 * so SDK types remain encapsulated away from the `:app` Kotlin classpath.
 */
public final class LiteRtMultiSession implements AutoCloseable {
    private final Interpreter interpreter;
    private final Map<Integer, Object> outputMap = new HashMap<>();

    public LiteRtMultiSession(ByteBuffer modelBuffer, int threads) {
        Interpreter.Options options = new Interpreter.Options();
        options.setNumThreads(Math.max(1, threads));
        this.interpreter = new Interpreter(modelBuffer, options);
        this.interpreter.allocateTensors();
    }

    public int inputCount() {
        return interpreter.getInputTensorCount();
    }

    public int outputCount() {
        return interpreter.getOutputTensorCount();
    }

    public int[] inputShape(int index) {
        return interpreter.getInputTensor(index).shape();
    }

    public int[] outputShape(int index) {
        return interpreter.getOutputTensor(index).shape();
    }

    public int inputElements(int index) {
        return interpreter.getInputTensor(index).numElements();
    }

    public int outputElements(int index) {
        return interpreter.getOutputTensor(index).numElements();
    }

    public String inputName(int index) {
        Tensor t = interpreter.getInputTensor(index);
        return t != null ? t.name() : "";
    }

    public String outputName(int index) {
        Tensor t = interpreter.getOutputTensor(index);
        return t != null ? t.name() : "";
    }

    public void runMultiple(ByteBuffer[] inputs, ByteBuffer[] outputs) {
        for (ByteBuffer input : inputs) {
            input.rewind();
        }
        outputMap.clear();
        for (int i = 0; i < outputs.length; i++) {
            outputs[i].rewind();
            outputMap.put(i, outputs[i]);
        }
        interpreter.runForMultipleInputsOutputs(inputs, outputMap);
        for (ByteBuffer output : outputs) {
            output.rewind();
        }
    }

    @Override
    public void close() {
        interpreter.close();
    }
}
