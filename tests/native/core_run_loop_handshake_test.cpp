#include "core_run_loop_handshake.h"

#include <cassert>
#include <chrono>
#include <thread>

using namespace std::chrono_literals;

int main() {
    // A shutdown arriving before the Core's reset marker must wait, not STOP.
    {
        CoreRunLoopHandshake handshake;
        handshake.reset();
        assert(handshake.waitForReadyOrReturn(1ms) ==
               CoreRunLoopHandshake::WaitResult::Timeout);
        handshake.markReady();
        assert(handshake.waitForReadyOrReturn(1ms) ==
               CoreRunLoopHandshake::WaitResult::Ready);
    }

    // If EXECUTE returns before reaching the marker, do not issue STOP.
    {
        CoreRunLoopHandshake handshake;
        handshake.reset();
        handshake.markExecuteReturned();
        assert(handshake.waitForReadyOrReturn(1ms) ==
               CoreRunLoopHandshake::WaitResult::ExecuteReturned);
        assert(!handshake.ready());
    }

    // STOP becomes eligible immediately after the marker.
    {
        CoreRunLoopHandshake handshake;
        handshake.reset();
        std::thread core([&handshake] {
            std::this_thread::sleep_for(2ms);
            handshake.markReady();
        });
        assert(handshake.waitForReadyOrReturn(100ms) ==
               CoreRunLoopHandshake::WaitResult::Ready);
        assert(handshake.ready());
        core.join();
    }

    // A return racing after readiness must not erase the readiness proof.
    {
        CoreRunLoopHandshake handshake;
        handshake.reset();
        handshake.markReady();
        std::thread core([&handshake] { handshake.markExecuteReturned(); });
        assert(handshake.waitForReadyOrReturn(1ms) ==
               CoreRunLoopHandshake::WaitResult::Ready);
        core.join();
        assert(handshake.ready());
    }
    return 0;
}
