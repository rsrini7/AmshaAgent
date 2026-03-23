import os
import re

# FQN: starts with java/jakarta/com/org/dev, then lowercase words, then dot, then Uppercase word
fqn_pattern = re.compile(r'\b((?:java|jakarta|com|org|dev)\.(?:[a-z_][\w_]*\.)+([A-Z][\w$]*))\b')

def is_in_comment_or_string(text, pos):
    # Search backwards for comment/string indicators on the same line
    lines = text[:pos].splitlines()
    if not lines: return False
    last_line = lines[-1]
    
    # Simple check for // comment
    if '//' in last_line:
        # Check if // is before pos (within this line)
        if last_line.find('//') < (pos - (len(text[:pos]) - len(last_line))):
            return True
    
    # Multi-line comment check
    last_open = text[:pos].rfind('/*')
    last_close = text[:pos].rfind('*/')
    if last_open > last_close:
        return True
        
    # Quotation mark check
    if last_line.count('"') % 2 == 1:
        return True
        
    return False

def get_module_name(path):
    if 'arcclaw-channel-api' in path: return 'api'
    if 'arcclaw-core' in path: return 'core'
    if 'arcclaw-app' in path: return 'app'
    if 'arcclaw-deployment' in path: return 'deployment'
    return 'unknown'

def should_skip_import(fqn, current_path):
    module = get_module_name(current_path)
    # api and core should not import app
    if module in ['api', 'core'] and fqn.startswith('com.arcclaw.app.'):
        return True
    return False

def organize_imports(lines, imports):
    def get_cat(x):
        if x.startswith('static '): return 0
        if x.startswith('java.'): return 1
        if x.startswith('javax.'): return 2
        if x.startswith('jakarta.'): return 3
        return 4

    final_imps = set()
    for imp in imports:
        if imp.startswith('java.lang.'):
            remainder = imp.replace('java.lang.', '')
            if '.' not in remainder: continue
        final_imps.add(imp)

    sorted_imps = sorted(list(final_imps), key=lambda x: (get_cat(x), x))
    
    output = []
    # Find package line
    package_line = next((l for l in lines if l.strip().startswith('package ')), "")
    if package_line:
        output.append(package_line)
        output.append("")
    
    prev_cat = -1
    for imp in sorted_imps:
        curr_cat = get_cat(imp)
        if prev_cat != -1 and curr_cat != prev_cat:
            output.append("")
        output.append(f"import {imp};")
        prev_cat = curr_cat
    
    if sorted_imps:
        output.append("")
        
    started = False
    for l in lines:
        s = l.strip()
        if s.startswith('package ') or s.startswith('import '): continue
        if not started and not s: continue
        started = True
        output.append(l)
        
    return output

def process_file(path):
    with open(path, 'r', encoding='utf-8') as f:
        content = f.read()

    lines = content.splitlines()
    body_lines = []
    imports = set()
    modified = False

    # Collect existing imports
    for line in lines:
        s = line.strip()
        if s.startswith('import '):
            imp = s.replace('import ', '').replace(';', '').strip()
            imports.add(imp)

    # Detect FQNs in body lines
    for line in lines:
        s = line.strip()
        if s.startswith('package ') or s.startswith('import '):
            body_lines.append(line)
            continue
            
        temp_line = line
        matches = list(fqn_pattern.finditer(temp_line))
        for m in reversed(matches):
            fqn = m.group(1)
            simple = m.group(2)
            pos = m.start()
            
            if is_in_comment_or_string(temp_line, pos): continue
            if '// fully qualified to disambiguate' in line: continue
            if should_skip_import(fqn, path): continue
            
            # Visibility guard (Caffeine internals)
            if 'caffeine.cache.' in fqn and (not simple.startswith('Caffeine')):
                 if '// fully qualified to disambiguate' not in temp_line:
                      temp_line += " // fully qualified to disambiguate"
                 continue

            collision = False
            for existing in imports:
                if existing.endswith('.' + simple) and existing != fqn:
                    collision = True
                    break
            
            if collision:
                if '// fully qualified to disambiguate' not in temp_line:
                    temp_line += " // fully qualified to disambiguate"
            else:
                temp_line = temp_line[:m.start()] + simple + temp_line[m.end():]
                imports.add(fqn)
                modified = True
        body_lines.append(temp_line)

    if not modified:
        # Even if not modified by FQN, let's at least re-organize the existing imports
        reorganized = organize_imports(lines, imports)
        if reorganized != lines:
            with open(path, 'w', encoding='utf-8') as f:
                f.write('\n'.join(reorganized) + '\n')
            return True
        return False

    output = organize_imports(body_lines, imports)
    with open(path, 'w', encoding='utf-8') as f:
        f.write('\n'.join(output) + '\n')
    return True

modified_count = 0
for root, dirs, files in os.walk('.'):
    if 'target' in dirs: dirs.remove('target')
    if '.git' in dirs: dirs.remove('.git')
    for name in files:
        if name.endswith('.java'):
            path = os.path.join(root, name)
            if process_file(path):
                modified_count += 1
print(f"Processed and organized {modified_count} files.")
