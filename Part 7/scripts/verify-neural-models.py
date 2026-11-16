#!/usr/bin/env python3
"""Verify shipped model digests and execute host inference. No microphone/network.
Host smoke tests establish tensor compatibility, not wake-word or speaker accuracy.
Install scripts/neural-model-requirements.txt in an isolated development venv.
"""
import hashlib
import json
from pathlib import Path
import numpy as np
from ai_edge_litert.interpreter import Interpreter
import onnxruntime as ort

root = Path(__file__).resolve().parents[1] / 'app/src/main/assets/openwakeword'
expected = {
    'melspectrogram.tflite': '96fa0adccb6e8cf95cb14465409a1a2898ee4a96a85bb9ed3c7eb0e68bf163e8',
    'embedding_model.tflite': 'c0aea21eb84a4ce90a08c870da41b7a7173b45269e6a3207c71d67c40f3a59d8',
    'hey_jarvis_v0.1.tflite': '14bff778604985e1b5c19f0f7bbe477a69cf281d8db34b232b3b972411f710e2',
    'silero_vad.onnx': 'a35ebf52fd3ce5f1469b2a36158dba761bc47b973ea3382b3186ca15b1f5af28',
}
for name, digest in expected.items():
    assert hashlib.sha256((root/name).read_bytes()).hexdigest() == digest, name

def load(name, resize=None):
    model=Interpreter(model_path=str(root/name), num_threads=1)
    if resize: model.resize_tensor_input(0, resize)
    model.allocate_tensors()
    return model

def infer(model, tensor):
    model.set_tensor(model.get_input_details()[0]['index'], tensor)
    model.invoke()
    result=model.get_tensor(model.get_output_details()[0]['index'])
    assert np.isfinite(result).all()
    return result

mel=load('melspectrogram.tflite', [1,1760])
embedding=load('embedding_model.tflite')
classifier=load('hey_jarvis_v0.1.tflite')
features=np.zeros((16,96),dtype=np.float32)
spectrum=np.ones((76,32),dtype=np.float32)
probabilities=[]
for _ in range(30):
    m=infer(mel,np.zeros((1,1760),dtype=np.float32)).reshape(8,32)
    spectrum[:-8]=spectrum[8:];spectrum[-8:]=m/10+2
    e=infer(embedding,spectrum.reshape(1,76,32,1)).reshape(96)
    features[:-1]=features[1:];features[-1]=e
    p=float(infer(classifier,features.reshape(1,16,96)).item())
    assert 0 <= p <= 1
    probabilities.append(p)
opts=ort.SessionOptions();opts.intra_op_num_threads=1;opts.inter_op_num_threads=1
speech=ort.InferenceSession(str(root/'silero_vad.onnx'),opts,providers=['CPUExecutionProvider'])
h=np.zeros((2,1,64),np.float32);c=h.copy()
vad=[]
for _ in range(30):
    p,h,c=speech.run(None,{'input':np.zeros((1,480),np.float32),'h':h,'c':c,'sr':np.array(16000,np.int64)})
    probability=float(p.item());assert np.isfinite(probability) and 0 <= probability < .5
    vad.append(probability)
turn_path=root.parent/'turn/smart-turn-v3.2-cpu.onnx'
assert hashlib.sha256(turn_path.read_bytes()).hexdigest() == '2bb026316b14a660486a75b1733cd3fbab8c2fd0314dc9af7be49f8cca967e4f'
turn=ort.InferenceSession(str(turn_path),opts,providers=['CPUExecutionProvider'])
turn_probability=float(turn.run(None,{'input_features':np.full((1,80,800),-1.5,np.float32)})[0].item())
assert np.isfinite(turn_probability) and 0<=turn_probability<=1
print(json.dumps({'digests':'PASS','openwakeword_frames':30,'silero_frames':30,
    'silent_wake_score_max':max(probabilities),'silent_vad_score_max':max(vad),
    'smart_turn_silent_input_probability':turn_probability,
    'scope':'host tensor/silence smoke only; no Android runtime/positive recognition/linguistic accuracy benchmark'},indent=2))
