import re
import os

app_yaml_path = "/Users/srinivasanragothaman/ws/AmshaAgentInternal/app/src/main/resources/application.yaml"
env_example_path = "/Users/srinivasanragothaman/ws/AmshaAgentInternal/.env.example"

# Extract all AMSHA_ env vars from application.yaml
with open(app_yaml_path) as f:
    yaml_content = f.read()

# Match ${AMSHA_VAR_NAME:...} or $AMSHA_VAR_NAME
env_vars_in_yaml = set(re.findall(r'\$(?:\{)?(AMSHA_[A-Z0-9_]+)', yaml_content))

# Extract all env vars from .env.example
with open(env_example_path) as f:
    env_content = f.read()

env_vars_in_example = set(re.findall(r'^(?:#\s*)?(AMSHA_[A-Z0-9_]+)\s*=', env_content, re.MULTILINE))

missing = env_vars_in_yaml - env_vars_in_example

print("Env vars in application.yaml:", len(env_vars_in_yaml))
print("Env vars in .env.example:", len(env_vars_in_example))
print("Missing env vars:")
for v in sorted(missing):
    print(f"  - {v}")
