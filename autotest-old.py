#!/usr/bin/env python3
import sys
import os
import argparse
import subprocess
import time
import re
import select

def parse_range(range_str, max_lines):
    """Parses ranges like '14-18', '14', 'all' into a set of 1-indexed line numbers."""
    if range_str.lower() == 'all':
        return set(range(1, max_lines + 1))
    
    lines = set()
    parts = range_str.split(',')
    for part in parts:
        if '-' in part:
            start, end = part.split('-')
            lines.update(range(int(start), int(end) + 1))
        else:
            lines.add(int(part))
    return lines

def main():
    parser = argparse.ArgumentParser(description="ArcClaw Automated Test Harness")
    parser.add_argument("-f", "--file", default="telegram-test-arcclaw.txt", help="Path to test cases file")
    parser.add_argument("-d", "--delay", type=float, default=5.0, help="Cooling delay between user prompts (seconds)")
    parser.add_argument("-a", "--approval-delay", type=float, default=2.0, help="Delay before auto-approving tool requests (seconds)")
    parser.add_argument("-r", "--range", default="all", help="1-indexed line numbers to run (e.g. '14-18', '14', or 'all')")
    parser.add_argument("-o", "--role", default="coordinator", help="Agent role (coordinator | researcher | debugger | etc.)")
    parser.add_argument("-v", "--verbose", action="store_true", help="Print verbose output of all subprocess logs")
    parser.add_argument("-p", "--port", default="8089", help="Http port for Quarkus server (default: 8089)")
    parser.add_argument("-l", "--log-file", help="Path to save the test execution log (optional)")
    args = parser.parse_args()

    if not os.path.exists(args.file):
        print(f"❌ Error: Test file not found: {args.file}")
        sys.exit(1)

    # Read and parse test lines
    with open(args.file, 'r', encoding='utf-8') as f:
        all_lines = f.readlines()

    total_lines = len(all_lines)
    target_lines = parse_range(args.range, total_lines)

    # Filter out empty lines or comments
    runnable_cases = []
    for idx, line in enumerate(all_lines, 1):
        cleaned = line.strip()
        if cleaned and idx in target_lines:
            # Skip lines starting with # as comments
            if cleaned.startswith("#"):
                continue
            runnable_cases.append((idx, cleaned))

    if not runnable_cases:
        print(f"⚠️ No runnable test cases selected in range '{args.range}'.")
        sys.exit(0)

    print(f"✦ Starting ArcClaw Automated Test Harness")
    print(f"  Test File      : {args.file}")
    print(f"  Target Range   : {args.range} ({len(runnable_cases)} active cases)")
    print(f"  Agent Role     : {args.role}")
    print(f"  Prompt Delay   : {args.delay}s")
    print(f"  Approval Delay : {args.approval_delay}s")
    print(f"-------------------------------------------------")

    log_f = None
    if args.log_file:
        log_f = open(args.log_file, 'w', encoding='utf-8')
        log_f.write("✦ Starting ArcClaw Automated Test Harness\n")
        log_f.write(f"  Test File      : {args.file}\n")
        log_f.write(f"  Target Range   : {args.range} ({len(runnable_cases)} active cases)\n")
        log_f.write(f"  Agent Role     : {args.role}\n")
        log_f.write(f"  Prompt Delay   : {args.delay}s\n")
        log_f.write(f"  Approval Delay : {args.approval_delay}s\n")
        log_f.write("-------------------------------------------------\n")
        log_f.flush()

    cmd = ["mise", "run", "jar:chat", "--role", args.role]
    proc = subprocess.Popen(
        cmd,
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        bufsize=1
    )

    import queue
    import threading

    q = queue.Queue()
    def reader_thread(stream, q):
        while True:
            try:
                char = stream.read(1)
                if not char:
                    break
                q.put(char)
            except Exception:
                break

    t = threading.Thread(target=reader_thread, args=(proc.stdout, q))
    t.daemon = True
    t.start()

    try:
        # Buffer to accumulate stdout lines
        output_buffer = ""
        case_index = 0
        awaiting_approval = False

        while proc.poll() is None or not q.empty():
            try:
                char = q.get(timeout=0.05)
                sys.stdout.write(char)
                sys.stdout.flush()
                output_buffer += char
                if log_f:
                    log_f.write(char)
                    log_f.flush()

                # Check for "You>" prompt to send the next case
                if "You>" in output_buffer:
                    output_buffer = "" # clear buffer
                    if awaiting_approval:
                        # We just responded to approval, wait for the actual output
                        awaiting_approval = False
                        continue

                    if case_index < len(runnable_cases):
                        idx, prompt = runnable_cases[case_index]
                        case_index += 1
                        
                        # Apply cooling delay to avoid token limit issues
                        print(f"\n[Test Harness] Waiting {args.delay}s cooling delay before sending case #{idx}...")
                        time.sleep(args.delay)
                        
                        print(f"\n[Test Harness] Sending Prompt #{idx}: {prompt}")
                        if log_f:
                            log_f.write(f"\n[Test Harness] Sending Prompt #{idx}: {prompt}\n")
                            log_f.flush()
                        proc.stdin.write(prompt + "\n")
                        proc.stdin.flush()
                    else:
                        print("\n[Test Harness] All test cases completed successfully. Exiting.")
                        if log_f:
                            log_f.write("\n[Test Harness] All test cases completed successfully. Exiting.\n")
                            log_f.write("/exit\n")
                            log_f.flush()
                        proc.stdin.write("/exit\n")
                        proc.stdin.flush()
                        break

                # Check for tool approval requests
                if "⏸ Approval needed for tool:" in output_buffer or "⏸ Approval pending" in output_buffer:
                    output_buffer = ""
                    awaiting_approval = True
                    print(f"\n[Test Harness] Tool approval detected. Waiting {args.approval_delay}s before auto-approving...")
                    time.sleep(args.approval_delay)
                    print("[Test Harness] Sending: /approve")
                    if log_f:
                        log_f.write("\n[Test Harness] Tool approval detected. Auto-approving after delay...\n")
                        log_f.write("/approve\n")
                        log_f.flush()
                    proc.stdin.write("/approve\n")
                    proc.stdin.flush()

            except queue.Empty:
                continue

    except KeyboardInterrupt:
        print("\n⚠️ Test execution interrupted by user.")
    finally:
        if proc.poll() is None:
            proc.terminate()
            proc.wait()
        if log_f:
            log_f.write("\n✦ Automated Test Harness Finished.\n")
            log_f.close()
        print("\n✦ Automated Test Harness Finished.")

if __name__ == "__main__":
    main()
