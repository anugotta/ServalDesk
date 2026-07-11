val script = """
            cat << 'EOF_MARKER' > ~/.vnc/xstartup
            #!/bin/bash
            export DISPLAY=:0
            
            # Setup an automatic apt hook so any newly installed app gets patched instantly
            cat << 'HOOK' > /usr/local/bin/patch-root-binaries.sh
            #!/bin/bash
            BINS=("/usr/share/code/code")
            for bin in "\${BINS[@]}"; do
                if [ -f "\$bin" ]; then
                    echo "patching"
                fi
            done
            HOOK
            chmod +x /usr/local/bin/patch-root-binaries.sh

            if command -v dbus-launch >/dev/null; then
                exec dbus-launch --exit-with-session startxfce4
            else
                exec startxfce4
            fi
            EOF_MARKER
""".trimIndent()
println("START")
println(script)
println("END")
