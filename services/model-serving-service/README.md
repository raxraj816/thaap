# model-serving-service

Later-phase model scoring service.

Planned stack:

- Python
- FastAPI
- scikit-learn
- MLflow
- SHAP

The first model should be a leakage-safe logistic regression baseline trained from PaymentAttempt-level datasets. XGBoost or LightGBM can be added only after EDA and baseline error analysis justify them.

The real-time contract must include score, risk level, model version, reason codes, and top factors. Timeout behavior must fall back to rules-only decisioning inside `payments-api`.
