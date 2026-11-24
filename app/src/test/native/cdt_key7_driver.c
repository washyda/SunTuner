/* Supply the platform identity on a host without Android's boot log or soc0. */
#define main cdt_tool_main
#include "cdt_tool.c"
#undef main

int main(int argc, char **argv) {
    if (argc != 9 && argc != 10) return cdt_tool_main(argc, argv);
    int inspect = strcmp(argv[1], "status-for-platform") == 0;
    if (!inspect && strcmp(argv[1], "enable-for-platform") != 0) return cdt_tool_main(argc, argv);

    uint8_t fields[5];
    for (size_t i = 0; i < sizeof(fields); ++i) {
        char *end;
        unsigned long value = strtoul(argv[4 + i], &end, 10);
        if (end == argv[4 + i] || *end != '\0' || value > UINT8_MAX) return 2;
        fields[i] = (uint8_t)value;
    }
    const platform_hint_t hint = {
        .state = PLATFORM_IDENTIFIED,
        .ver = fields[0],
        .platform_id = fields[1],
        .major = fields[2],
        .minor = fields[3],
        .subtype = fields[4],
        .requires_template = argc == 10 && strcmp(argv[9], "--require-template") == 0,
    };
    return inspect ? cmd_status_with_hint(argv[2], argv[3], &hint)
                   : cmd_enable_with_hint(argv[2], argv[3], &hint);
}
