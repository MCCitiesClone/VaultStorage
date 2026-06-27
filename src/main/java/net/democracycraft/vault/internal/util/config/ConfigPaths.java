package net.democracycraft.vault.internal.util.config;

public enum ConfigPaths {
    MYSQL_HOST("mysql.host"),
    MYSQL_PORT("mysql.port"),
    MYSQL_DATABASE("mysql.database"),
    MYSQL_USER("mysql.user"),
    MYSQL_PASSWORD("mysql.password"),
    MYSQL_USE_SSL("mysql.useSSL"),
    SCAN_BATCH_SIZE("scan.batch-size"),
    SCAN_CACHE_TTL_SECONDS("scan.cache-ttl-seconds"),
    SCAN_COOLDOWN_SECONDS("scan.cooldown-seconds"),
    AUTOVAULT_ENABLED("auto-vault.enabled"),
    AUTOVAULT_DELAY_TICKS("auto-vault.delay-ticks");

    private final String path;

    ConfigPaths(String path) {
        this.path = path;
    }

    public String getPath() {
        return path;
    }
}
