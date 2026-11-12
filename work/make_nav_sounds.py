import math, random, struct, wave
RATE = 44100
def write(path, secs, sample):
    n = int(RATE * secs)
    peak = max(abs(sample(i / RATE)) for i in range(n)) or 1
    with wave.open(path, 'wb') as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(RATE)
        w.writeframes(b''.join(struct.pack('<h', int(30000 * sample(i / RATE) / peak)) for i in range(n)))
def fade(t, attack):            # no click at the first sample
    return min(1.0, t / attack)
random.seed(7)
noise = [random.uniform(-1, 1) for _ in range(RATE)]
def move(t):                    # a soft, short "tk": a high sine that falls a little and dies in a few ms, over a breath of air
    f = 1250 + 500 * math.exp(-t / 0.006)
    tone = math.sin(2 * math.pi * f * t) * math.exp(-t / 0.011)
    air = noise[int(t * RATE)] * math.exp(-t / 0.003) * 0.18
    return (tone + air) * fade(t, 0.0012)
def select(t):                  # a rounder, lower "tok" with a fifth above it, a little longer
    f = 430 + 90 * math.exp(-t / 0.02)
    body = math.sin(2 * math.pi * f * t) * math.exp(-t / 0.045)
    fifth = math.sin(2 * math.pi * f * 1.5 * t) * math.exp(-t / 0.03) * 0.45
    return (body + fifth) * fade(t, 0.002)
write('launcher/app/src/main/res/raw/nav_move.wav', 0.07, move)
write('launcher/app/src/main/res/raw/nav_select.wav', 0.22, select)
