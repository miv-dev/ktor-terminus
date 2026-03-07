package miv.dev.ru.forms

import miv.dev.ru.domain.*

object SeedData {

    val clientFormV1 = FormSchema(
        id = "client_form",
        name = "Заявка клиента",
        fields = listOf(
            FormField("client_type", "Тип клиента", FieldType.SELECT,
                placeholder = "Выберите тип клиента",
                options = listOf("физлицо", "юрлицо")),
            FormField("full_name", "ФИО", FieldType.TEXT, "Иванов Иван Иванович"),
            FormField("email", "Email", FieldType.EMAIL, "mail@example.com"),
            FormField("phone", "Телефон", FieldType.PHONE, "+7 (999) 000-00-00"),
            FormField("inn", "ИНН", FieldType.TEXT, "1234567890"),
            FormField("kpp", "КПП", FieldType.TEXT, "123456789"),
            FormField("passport", "Серия и номер паспорта", FieldType.TEXT, "1234 567890")
        )
    )

    val rulesV1 = RuleSet(
        formId = "client_form",
        lpContent = """
#script (python)
import clingo

def has_suffix(value, suffix):
    return clingo.String("true" if value.string.endswith(suffix.string) else "false")

def has_at(value):
    return clingo.String("true" if "@" in value.string else "false")
#end.

% ─── Visibility ─────────────────────────────────────────────

visible(client_type).
visible(full_name).
visible(email).
visible(phone).

visible(inn)      :- field_value(client_type, "юрлицо").
visible(kpp)      :- field_value(client_type, "юрлицо").
visible(passport) :- field_value(client_type, "физлицо").

hidden(F) :- field(F), not visible(F).

% ─── Required ────────────────────────────────────────────────

required(client_type).
required(full_name).
required(F) :- visible(F), F = inn.
required(F) :- visible(F), F = kpp.
required(F) :- visible(F), F = passport.

% email required only for физлицо
required(email) :- field_value(client_type, "физлицо").

% phone required only after email is valid
required(phone) :- valid(email).

% ─── Read-only ───────────────────────────────────────────────

readonly(F) :- user_role("viewer"), field(F).

% ─── Validation ──────────────────────────────────────────────

valid(inn) :- field_value(inn, V), #count{ C : string_code(_,V,C) } = 10.
valid(inn) :- field_value(inn, V), #count{ C : string_code(_,V,C) } = 12.
invalid(inn, "ИНН: 10 цифр для ЮЛ, 12 для ФЛ") :-
    visible(inn), field_value(inn, _), not valid(inn).

valid(kpp) :- field_value(kpp, V), #count{ C : string_code(_,V,C) } = 9.
invalid(kpp, "КПП должен быть 9 символов") :-
    visible(kpp), field_value(kpp, _), not valid(kpp).

% ─── Hints (email domain specifics) ─────────────────────────

hint(email, "gmail_specific")  :- field_value(email, V), @has_suffix(V, "@gmail.com") = "true".
hint(email, "yandex_specific") :- field_value(email, V), @has_suffix(V, "@yandex.ru")  = "true".
hint(email, "mailru_specific") :- field_value(email, V), @has_suffix(V, "@mail.ru")    = "true".
hint(email, "corp_email") :-
    field_value(email, V),
    @has_at(V) = "true",
    not hint(email, "gmail_specific"),
    not hint(email, "yandex_specific"),
    not hint(email, "mailru_specific").

% ─── Submit ──────────────────────────────────────────────────

submit_allowed :-
    not invalid_required_exists.
invalid_required_exists :-
    required(F), visible(F), invalid(F, _).
invalid_required_exists :-
    required(F), visible(F), not field_value(F, _).
        """.trimIndent()
    )

    /** v2: add `comment` field (always visible, not required), phone becomes always required */
    val clientFormV2 = clientFormV1.copy(
        fields = clientFormV1.fields + FormField("comment", "Комментарий", FieldType.TEXT, "Необязательный комментарий")
    )

    val rulesV2 = rulesV1.copy(
        lpContent = rulesV1.lpContent
            .replace(
                "% phone required only after email is valid\nrequired(phone) :- valid(email).",
                "% phone is always required\nrequired(phone)."
            )
            .replace(
                "visible(client_type).",
                "visible(client_type).\nvisible(comment)."
            )
    )
}
