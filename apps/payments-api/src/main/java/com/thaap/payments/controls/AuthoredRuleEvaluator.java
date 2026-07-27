package com.thaap.payments.controls;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class AuthoredRuleEvaluator {

    public boolean matches(AuthoredRuleNode node, Map<String, Object> features) {
        if (node instanceof AuthoredRuleNode.Group group) {
            return group.mode() == AuthoredRuleNode.Mode.ALL
                    ? group.clauses().stream().allMatch(clause -> matches(clause, features))
                    : group.clauses().stream().anyMatch(clause -> matches(clause, features));
        }
        AuthoredRuleNode.Predicate predicate = (AuthoredRuleNode.Predicate) node;
        Object actual = features.get(predicate.feature());
        if (actual == null) {
            return false;
        }
        return switch (predicate.operator()) {
            case EQ -> actual.equals(predicate.value());
            case IN -> ((List<?>) predicate.value()).contains(actual);
            case GT -> ((Long) actual) > ((Long) predicate.value());
            case GTE -> ((Long) actual) >= ((Long) predicate.value());
            case LT -> ((Long) actual) < ((Long) predicate.value());
            case LTE -> ((Long) actual) <= ((Long) predicate.value());
        };
    }
}
