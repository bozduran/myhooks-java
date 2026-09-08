package com.myhooks.discover;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.myhooks.git.GitStaged;
import java.util.List;
import org.junit.jupiter.api.Test;

class FileDiscoveryTest {

    @Test
    void filtersJrxmlCaseInsensitively() {
        FileDiscovery discovery = new FileDiscovery();
        assertEquals(List.of("a.jrxml", "b.JRXML"),
                discovery.files(List.of("a.jrxml", "b.JRXML", "c.xml", "d.txt")));
    }

    @Test
    void usesArgsWhenGiven() {
        FileDiscovery discovery = new FileDiscovery();
        assertEquals(List.of("x.jrxml"), discovery.files(List.of("x.jrxml")));
    }

    @Test
    void fallsBackToStagedWhenNoArgs() {
        GitStaged git = new GitStaged(args -> "staged.jrxml\nother.xml\n");
        FileDiscovery discovery = new FileDiscovery(git);
        assertEquals(List.of("staged.jrxml"), discovery.files(List.of()));
    }

    @Test
    void returnsEmptyWhenNoJrxml() {
        FileDiscovery discovery = new FileDiscovery();
        assertEquals(List.of(), discovery.files(List.of("a.txt", "b.xml")));
    }
}
