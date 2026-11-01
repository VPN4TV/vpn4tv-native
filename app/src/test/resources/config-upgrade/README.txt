Configs and bridge sidecars as the generator of 5.3.0-5.3.3 wrote them to disk
(<name>.json, <name>.json.<bridge>.json), bridge port base 30000. Keys are made
up, addresses are RFC 5737 documentation ones. ConfigUpgradeTest upgrades them
and compares the result with what the current generator writes for the same
proxies (the proxies are defined in the test).
