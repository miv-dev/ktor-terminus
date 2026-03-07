package miv.dev.ru.forms

import miv.dev.ru.domain.*

object SeedData {

    /** v1 — initial schema */
    val clientFormV1 = FormSchema(
        id = "client_form",
        name = "Заявка клиента",
        description = "Форма подачи клиентской заявки",
        fields = listOf(
            FormField(
                id = "client_type",
                label = "Тип клиента",
                type = FieldType.SELECT,
                placeholder = "Выберите тип клиента",
                validation = listOf(ValidationRule.Required),
                visibility = VisibilityRule.Always
            ),
            FormField(
                id = "full_name",
                label = "ФИО",
                type = FieldType.TEXT,
                placeholder = "Иванов Иван Иванович",
                validation = listOf(ValidationRule.Required, ValidationRule.MinLength(2)),
                visibility = VisibilityRule.Always
            ),
            FormField(
                id = "email",
                label = "Email",
                type = FieldType.EMAIL,
                placeholder = "mail@example.com",
                validation = listOf(
                    ValidationRule.Required,
                    ValidationRule.MatchesRegex("^[^@]+@[^@]+\\.[^@]+\$")
                ),
                visibility = VisibilityRule.Always
            ),
            FormField(
                id = "inn",
                label = "ИНН",
                type = FieldType.TEXT,
                placeholder = "1234567890",
                validation = listOf(
                    ValidationRule.Required,
                    ValidationRule.MinLength(10),
                    ValidationRule.MaxLength(12)
                ),
                visibility = VisibilityRule.WhenEquals("client_type", "юрлицо")
            ),
            FormField(
                id = "kpp",
                label = "КПП",
                type = FieldType.TEXT,
                placeholder = "123456789",
                validation = listOf(
                    ValidationRule.Required,
                    ValidationRule.MinLength(9),
                    ValidationRule.MaxLength(9)
                ),
                visibility = VisibilityRule.WhenEquals("client_type", "юрлицо")
            ),
            FormField(
                id = "passport",
                label = "Серия и номер паспорта",
                type = FieldType.TEXT,
                placeholder = "1234 567890",
                validation = listOf(
                    ValidationRule.Required,
                    ValidationRule.MatchesRegex("^\\d{4} \\d{6}\$")
                ),
                visibility = VisibilityRule.WhenEquals("client_type", "физлицо")
            )
        )
    )

    /** v2 — adds `phone` field, email becomes optional for юрлицо */
    val clientFormV2 = clientFormV1.copy(
        fields = clientFormV1.fields.map { field ->
            when (field.id) {
                "email" -> field.copy(
                    validation = listOf(
                        ValidationRule.RequiredWhen(
                            VisibilityRule.WhenEquals("client_type", "физлицо")
                        ),
                        ValidationRule.MatchesRegex("^[^@]+@[^@]+\\.[^@]+\$")
                    )
                )
                else -> field
            }
        } + listOf(
            FormField(
                id = "phone",
                label = "Телефон",
                type = FieldType.TEXT,
                placeholder = "+7 (999) 000-00-00",
                validation = listOf(ValidationRule.Required),
                visibility = VisibilityRule.Always
            )
        )
    )
}
