package com.dbbaskette.issuebot.controller;

import com.dbbaskette.issuebot.service.ui.DashboardControlRoomAssembler.ControlRoom;
import com.dbbaskette.issuebot.service.ui.DashboardControlRoomAssembler.Lane;

import java.util.List;

final class DashboardRenderFixtures {

    private DashboardRenderFixtures() {
    }

    static ControlRoom emptyControlRoom() {
        return new ControlRoom(
                new Lane("needs-decision", "Intervention", "Needs your decision",
                        "No decisions need you right now.", "/inbox", 0, List.of()),
                new Lane("processing", "Execution", "Currently processing",
                        "IssueBot is not processing an issue.", "/issues?status=IN_PROGRESS", 0, List.of()),
                new Lane("up-next", "Queue", "Up next",
                        "No issues are waiting to run.", "/issues", 0, List.of()));
    }
}
