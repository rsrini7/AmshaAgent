#!/usr/bin/env python3
import os
import re
import sys
import urllib.parse

SKIPPED_DIRECTORY_NAMES = {
    '.a5c',
    '.cache',
    '.devbox',
    '.gemini',
    '.git',
    '.openclaude',
    '.kimchi',
    '.omm',
    '.headroom',
    '.commandcode',
    '.scratch',
    '.workbuddy-ai',
    '.venv',
    '__pycache__',
    'classifier-cache',
    'node_modules',
    'target',
    'venv',
}

# List of root dirs in the project
REAL_ROOT_DIRS = {
    'app', 'core', 'channel-api', 'channel-telegram', 'channel-web', 'common',
    'deployment', 'mcp', 'onboarding', 'persistence-api', 'persistence-common',
    'persistence-fs', 'persistence-postgres', 'persistence-sqlite', 'rag',
    'rest-http', 'ui', 'docs'
}

# Mapping of artifactId to directory name
ARTIFACT_TO_DIR = {
    'amsha-app': 'app',
    'amsha-core': 'core',
    'amsha-channel-api': 'channel-api',
    'amsha-channel-telegram': 'channel-telegram',
    'amsha-channel-web': 'channel-web',
    'amsha-common': 'common',
    'amsha-deployment': 'deployment',
    'amsha-mcp': 'mcp',
    'amsha-onboarding': 'onboarding',
    'amsha-persistence-api': 'persistence-api',
    'amsha-persistence-common': 'persistence-common',
    'amsha-persistence-fs': 'persistence-fs',
    'amsha-persistence-postgres': 'persistence-postgres',
    'amsha-persistence-sqlite': 'persistence-sqlite',
    'amsha-rag': 'rag',
    'amsha-ui': 'ui'
}

def gfm_anchor(heading):
    # GFM anchor generation rules:
    # 1. Lowercase
    # 2. Remove all punctuation (keep only letters, numbers, spaces, and hyphens)
    # 3. Replace spaces with hyphens
    # 4. GFM does NOT collapse multiple hyphens.
    heading = heading.lower().strip()
    heading = re.sub(r'<[^>]+>', '', heading) # Remove HTML tags
    heading = heading.replace('`', '') # Strip backticks

    anchor = ""
    for char in heading:
        if char.isalnum() or char in (' ', '-', '_'):
            anchor += char
    anchor = anchor.replace(' ', '-')
    return anchor.strip('-')

def scan_markdown_files(workspace_root):
    md_files = []
    for root, dirs, files in os.walk(workspace_root):
        dirs[:] = [directory for directory in dirs if directory not in SKIPPED_DIRECTORY_NAMES]
        for file in files:
            if file.endswith('.md'):
                md_files.append(os.path.join(root, file))
    return md_files

def get_file_headings(md_path):
    headings = []
    with open(md_path, 'r', encoding='utf-8', errors='ignore') as f:
        content = f.read()

    # Remove code blocks
    content_clean = re.sub(r'```.*?```', '', content, flags=re.DOTALL)

    for line in content_clean.splitlines():
        match = re.match(r'^(#{1,6})\s+(.+)$', line)
        if match:
            headings.append(match.group(2))
    return headings

def extract_file_anchors(md_path):
    headings = get_file_headings(md_path)
    anchors = set()
    anchor_counts = {}
    for h in headings:
        base_anchor = gfm_anchor(h)
        if base_anchor in anchor_counts:
            anchor_counts[base_anchor] += 1
            anchor = f"{base_anchor}-{anchor_counts[base_anchor]}"
        else:
            anchor_counts[base_anchor] = 0
            anchor = base_anchor
        anchors.add(anchor)
    return anchors

VALID_ADR_STATUS_PREFIXES = {
    'accepted', 'implemented', 'proposed', 'approved', 'deferred', 'rejected', 'closed'
}

def check_adr_metadata(md_path, workspace_root, lines_raw):
    errors = []
    rel_path = os.path.relpath(md_path, workspace_root)
    norm_rel = os.path.normpath(rel_path)
    adr_prefix = os.path.normpath("docs/adr") + os.sep
    if not norm_rel.startswith(adr_prefix):
        return errors

    if not lines_raw or not lines_raw[0].startswith('# '):
        errors.append("ADR must start with a level-1 heading ('# Title') on line 1")

    status_val = None
    for line in lines_raw[:15]:
        m = re.match(r"^-\s+\*\*Status\*\*:\s*(.+)$", line.strip())
        if m:
            status_val = m.group(1).strip()
            break

    if not status_val:
        errors.append("ADR missing required '- **Status**:' metadata line in header (first 15 lines)")
    else:
        words = re.findall(r"[A-Za-z]+", status_val)
        if not words or words[0].lower() not in VALID_ADR_STATUS_PREFIXES:
            allowed = ", ".join(sorted(VALID_ADR_STATUS_PREFIXES))
            errors.append(f"ADR has invalid status '{status_val}'. Leading keyword must be one of: {allowed}")

    return errors

def check_md_file(md_path, workspace_root, file_anchors):
    with open(md_path, 'r', encoding='utf-8', errors='ignore') as f:
        content = f.read()

    # Remove code blocks for anchor checking
    content_clean = re.sub(r'```.*?```', '', content, flags=re.DOTALL)
    lines_clean = content_clean.splitlines()
    lines_raw = content.splitlines()

    errors = []
    warnings = []

    # Validate ADR metadata if file is located in docs/adr/
    errors.extend(check_adr_metadata(md_path, workspace_root, lines_raw))

    link_pattern = re.compile(r'!?\[([^\]]*?)\]\(([^)]+?)\)')

    # Check links and anchors line by line
    for line_idx, line in enumerate(lines_raw, 1):
        # 1. Directory prefix checks (warnings)
        for artifact, real_dir in ARTIFACT_TO_DIR.items():
            for pattern in [f"`{artifact}/", f"/{artifact}/", f"./{artifact}/", f"`{artifact}-", f"/{artifact}-", f"./{artifact}-"]:
                if pattern in line:
                    warnings.append(
                        f"Line {line_idx}: Potential directory prefix mismatch: '{pattern}' (should be directory name '{real_dir}' without 'amsha-')"
                    )

            # Wildcard prefixes
            wildcard_patterns = [
                r'amsha-persistence-\*\/',
                r'amsha-channel-\*\/',
            ]
            for wp in wildcard_patterns:
                if re.search(wp, line):
                    warnings.append(f"Line {line_idx}: Incorrect wildcard directory listing prefix in: '{line.strip()}'")

        # 2. Link target checks (errors)
        for match in link_pattern.finditer(line):
            link_target = match.group(2).strip()

            # Skip web URLs or local fragment anchors
            if (link_target.startswith('http://') or
                link_target.startswith('https://') or
                link_target.startswith('mailto:') or
                link_target.startswith('#') or
                link_target.startswith('img.shields.io') or
                link_target.startswith('badge/')):
                continue

            parsed = urllib.parse.urlparse(link_target)
            target_path = parsed.path
            fragment = parsed.fragment

            if not target_path and not fragment:
                continue

            # Skip code snippets like [byte](Get-Random -Max 256)
            if target_path and ' ' in target_path:
                continue

            # Decode URL characters
            target_path = urllib.parse.unquote(target_path)

            # Resolve target file path
            md_dir = os.path.dirname(md_path)
            if not target_path:
                target_file = md_path
            elif target_path.startswith('/'):
                target_file = os.path.join(workspace_root, target_path.lstrip('/'))
            else:
                target_file = os.path.abspath(os.path.join(md_dir, target_path))

            # Verify file exists
            if not os.path.exists(target_file):
                errors.append(f"Line {line_idx}: Broken link target '{link_target}' (Resolved path not found: {target_file})")
                continue

            # Verify anchor exists in target file if fragment is present
            if fragment:
                if not target_file.endswith('.md'):
                    continue
                if target_file not in file_anchors:
                    file_anchors[target_file] = extract_file_anchors(target_file)
                anchors_in_target = file_anchors.get(target_file, set())
                decoded_fragment = urllib.parse.unquote(fragment).lower().strip()

                if decoded_fragment not in anchors_in_target:
                    # Look for potential matches or suggestions
                    matches = [v for v in anchors_in_target if decoded_fragment in v or v in decoded_fragment]
                    if not matches:
                        normalized_fragment = re.sub(r'-+', '-', decoded_fragment)
                        matches = [v for v in anchors_in_target if re.sub(r'-+', '-', v) == normalized_fragment]
                    suggestion = f" (Did you mean: {matches}?)" if matches else ""
                    errors.append(f"Line {line_idx}: Anchor '#{fragment}' not found in target file '{os.path.basename(target_file)}'{suggestion}")

    return errors, warnings

def main():
    # Use script parent directory as workspace root
    _lib_dir = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "lib")
    if _lib_dir not in sys.path:
        sys.path.insert(0, _lib_dir)
    from repo_root import get_repo_root
    workspace_root = str(get_repo_root(__file__))
    md_files = scan_markdown_files(workspace_root)

    print(f"Scanning {len(md_files)} markdown files in '{workspace_root}'...\n")

    # Step 1: Collect headings/anchors from all files
    file_anchors = {}
    for md in md_files:
        file_anchors[md] = extract_file_anchors(md)

    # Step 2: Validate all files
    total_errors = 0
    total_warnings = 0

    for md in sorted(md_files):
        errors, warnings = check_md_file(md, workspace_root, file_anchors)

        if errors or warnings:
            rel_path = os.path.relpath(md, workspace_root)
            print(f"=== {rel_path} ===")
            for err in errors:
                print(f"  [ERROR] {err}")
                total_errors += 1
            for warn in warnings:
                print(f"  [WARN] {warn}")
                total_warnings += 1
            print()

    print(f"Scan complete: found {total_errors} errors and {total_warnings} warnings.")
    if total_errors > 0 or total_warnings > 0:
        sys.exit(1)
    else:
        sys.exit(0)

if __name__ == "__main__":
    main()
