# Architecture

Target native ARM11 homebrew with devkitPro, libctru and Citro2D/Citro3D. Evaluate Java-to-native options before committing to a portability strategy. Keep graphics, audio, input and platform services behind interfaces so the same game simulation and networking rules can run on both 3DS generations and in host-side tests.

Track toolchain builds, emulator boot, physical hardware boot, gameplay parity and upstream multiplayer independently. Record hardware model, build SHA and test scenario alongside measurements. Audit upstream and dependency licenses before importing code or assets.
