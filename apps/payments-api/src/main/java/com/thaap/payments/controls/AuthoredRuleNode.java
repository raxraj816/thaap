package com.thaap.payments.controls;

import java.util.List;

public sealed interface AuthoredRuleNode permits AuthoredRuleNode.Group, AuthoredRuleNode.Predicate {

    record Group(Mode mode, List<AuthoredRuleNode> clauses) implements AuthoredRuleNode {
        public Group {
            clauses = List.copyOf(clauses);
        }
    }

    record Predicate(String feature, AuthoredRuleOperator operator, Object value) implements AuthoredRuleNode { }

    enum Mode {
        ALL,
        ANY
    }
}
