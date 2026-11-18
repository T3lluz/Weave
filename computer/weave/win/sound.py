"""What Windows is playing, as raw sound for ffmpeg to encode.

ffmpeg has no way to hear a Windows PC's own output, so a small helper does: it opens the
default speakers in loopback (WASAPI) and writes what they play to its standard output as
16-bit stereo at 48 kHz. Windows itself converts whatever the speakers are set to. When nothing
plays, Windows sends nothing at all, and ffmpeg would hold the picture back waiting for sound,
so the helper fills every gap with silence.

The helper is C#, compiled once on the PC by the compiler every Windows has (PowerShell's
Add-Type): nothing to download, and its source is here to read.
"""

import subprocess

from . import helpers

NO_WINDOW = 0x08000000
# what the helper writes, for ffmpeg's input
PCM_ARGS = ["-f", "s16le", "-ar", "48000", "-ac", "2"]

SOURCE = r'''
using System;
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Threading;

[ComImport, Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")]
class MMDeviceEnumerator { }

[ComImport, Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
interface IMMDeviceEnumerator {
    [PreserveSig] int EnumAudioEndpoints(int dataFlow, int stateMask, out IntPtr devices);
    [PreserveSig] int GetDefaultAudioEndpoint(int dataFlow, int role, out IMMDevice device);
}

[ComImport, Guid("D666063F-1587-4E43-81F1-B948E807363F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
interface IMMDevice {
    [PreserveSig] int Activate(ref Guid iid, int clsCtx, IntPtr activationParams, [MarshalAs(UnmanagedType.IUnknown)] out object iface);
}

[ComImport, Guid("1CB9AD4C-DBFA-4c32-B178-C2F568A703B2"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
interface IAudioClient {
    [PreserveSig] int Initialize(int shareMode, int streamFlags, long bufferDuration, long periodicity, ref WAVEFORMATEX format, IntPtr sessionGuid);
    [PreserveSig] int GetBufferSize(out uint frames);
    [PreserveSig] int GetStreamLatency(out long latency);
    [PreserveSig] int GetCurrentPadding(out uint padding);
    [PreserveSig] int IsFormatSupported(int shareMode, ref WAVEFORMATEX format, out IntPtr closest);
    [PreserveSig] int GetMixFormat(out IntPtr format);
    [PreserveSig] int GetDevicePeriod(out long defaultPeriod, out long minPeriod);
    [PreserveSig] int Start();
    [PreserveSig] int Stop();
    [PreserveSig] int Reset();
    [PreserveSig] int SetEventHandle(IntPtr handle);
    [PreserveSig] int GetService(ref Guid iid, [MarshalAs(UnmanagedType.IUnknown)] out object service);
}

[ComImport, Guid("C8ADBD64-E71E-48a0-A4DE-185C395CD317"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
interface IAudioCaptureClient {
    [PreserveSig] int GetBuffer(out IntPtr data, out uint frames, out uint flags, out ulong devicePosition, out ulong qpcPosition);
    [PreserveSig] int ReleaseBuffer(uint frames);
    [PreserveSig] int GetNextPacketSize(out uint frames);
}

[StructLayout(LayoutKind.Sequential, Pack = 2)]
struct WAVEFORMATEX {
    public ushort wFormatTag, nChannels;
    public uint nSamplesPerSec, nAvgBytesPerSec;
    public ushort nBlockAlign, wBitsPerSample, cbSize;
}

static class WeaveSound {
    const int Rate = 48000, FrameBytes = 4;

    static void Check(int hr, string what) {
        if (hr < 0) { Console.Error.WriteLine("weave-sound: " + what + " failed: 0x" + hr.ToString("X8")); Environment.Exit(2); }
    }

    [MTAThread]
    static int Main() {
        IMMDeviceEnumerator devices = (IMMDeviceEnumerator)new MMDeviceEnumerator();
        IMMDevice speakers;
        Check(devices.GetDefaultAudioEndpoint(0, 0, out speakers), "finding the speakers");
        Guid clientId = typeof(IAudioClient).GUID;
        object o;
        Check(speakers.Activate(ref clientId, 23, IntPtr.Zero, out o), "opening the speakers");
        IAudioClient client = (IAudioClient)o;
        WAVEFORMATEX format = new WAVEFORMATEX();
        format.wFormatTag = 1; format.nChannels = 2; format.nSamplesPerSec = Rate; format.wBitsPerSample = 16;
        format.nBlockAlign = FrameBytes; format.nAvgBytesPerSec = Rate * FrameBytes; format.cbSize = 0;
        // loopback, and Windows converts from whatever the speakers are set to
        int flags = 0x00020000 | unchecked((int)0x80000000) | 0x08000000;
        Check(client.Initialize(0, flags, 2000000, 0, ref format, IntPtr.Zero), "listening to the speakers");
        Guid captureId = typeof(IAudioCaptureClient).GUID;
        Check(client.GetService(ref captureId, out o), "reading the speakers");
        IAudioCaptureClient capture = (IAudioCaptureClient)o;
        Check(client.Start(), "starting");

        Stream output = Console.OpenStandardOutput();
        Stopwatch clock = Stopwatch.StartNew();
        long written = 0;
        byte[] block = new byte[1 << 16];
        byte[] silence = new byte[480 * FrameBytes];
        try {
            while (true) {
                uint waiting;
                Check(capture.GetNextPacketSize(out waiting), "the next packet");
                while (waiting > 0) {
                    IntPtr data; uint frames, bufferFlags; ulong a, b;
                    Check(capture.GetBuffer(out data, out frames, out bufferFlags, out a, out b), "the packet");
                    int bytes = (int)frames * FrameBytes;
                    if (block.Length < bytes) block = new byte[bytes];
                    if ((bufferFlags & 2) != 0) Array.Clear(block, 0, bytes); else Marshal.Copy(data, block, 0, bytes);
                    capture.ReleaseBuffer(frames);
                    output.Write(block, 0, bytes);
                    written += frames;
                    Check(capture.GetNextPacketSize(out waiting), "the next packet");
                }
                // nothing playing means nothing arrives: keep the stream going with silence, a little behind the clock
                long due = clock.ElapsedMilliseconds * (Rate / 1000) - Rate / 25;
                while (written < due) { output.Write(silence, 0, silence.Length); written += 480; }
                output.Flush();
                Thread.Sleep(5);
            }
        } catch (IOException) {
            return 0;   // whoever was reading has gone
        }
    }
}
'''

async def helper() -> str | None:
    """The helper's path, compiled first if this PC hasn't yet; None when it can't be built."""
    return await helpers.build("weave-sound", SOURCE)


def listen(exe: str) -> subprocess.Popen:
    """Starts the helper. Its standard output is the sound, to hand to ffmpeg as its input."""
    return subprocess.Popen([exe], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, stdin=subprocess.DEVNULL, creationflags=NO_WINDOW)
