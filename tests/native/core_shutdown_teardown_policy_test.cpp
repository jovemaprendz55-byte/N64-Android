#include "core_shutdown_teardown_policy.h"

#include <cassert>

int main() {
    assert(!coreShutdownRequiresGraphicsContext(false, false));
    assert(coreShutdownRequiresGraphicsContext(true, false));
    assert(coreShutdownRequiresGraphicsContext(false, true));
    assert(coreShutdownRequiresGraphicsContext(true, true));
    return 0;
}
