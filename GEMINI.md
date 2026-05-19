Windows OS:
In Windows Do not use dir /s /b or grep which are keep on failing right use powershell frindly commands to find files also use rg (ripgrep) which is already installed in my pc alternative to grep.
Do not use  cat -n instead use powershell if in windows terminal.

For actual data from sqlite use sqlite3 data/arcclaw-meta.db ".tables".
For vec or search use sqlite3 data/arcclaw-fts.db ".tables"
Then drill into respective tables.

