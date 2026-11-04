# Компоненты предмета

[Xero](../README.md) · [Действия](actions/README.md) · [События](events.md) · [Игровые значения](values.md) · [Плейсхолдеры](placeholders.md) · [Частицы, зелья, звуки](effects.md) · [Компоненты](components.md)

Всё, что не покрывают поля `item(…)`, пишется в `components:` так же, как в `/give`. `minecraft:` можно не писать.

```kotlin
x = item(stick, components: "{max_stack_size:1,rarity:'epic'}")
```

| Компонент | Название | Что значит | Пример |
|:--|:--|:--|:--|
| `additional_trade_cost` | доплата в сделке | Служебный: прибавка к цене сделки жителя. | `1` |
| `attack_range` | дальность удара | Дальность атаки и запас хитбокса цели. | `{max_reach:5.0f}` |
| `attribute_modifiers` | атрибуты | Меняет атрибуты того, кто держит или носит предмет в нужном слоте. | `[{type:"minecraft:scale",slot:"hand",id:"xerocode:grow",amount:1.0d,operation:"add_multiplied_base"}]` |
| `axolotl/variant` | аксолотль: окрас | Каким появится существо из яйца призыва, ведра или другого предмета. | `"blue"` |
| `banner_patterns` | узоры флага | Слои узоров флага или щита. | `[{pattern:"minecraft:triangle_top",color:"red"}]` |
| `base_color` | цвет щита | Основной цвет флага на щите. | `"lime"` |
| `bees` | пчёлы в улье | Сущности в улье и сколько они там пробыли. | `[{entity_data:{id:"minecraft:bee"},min_ticks_in_hive:60,ticks_in_hive:0}]` |
| `block_entity_data` | данные блока | NBT блок-сущности при установке. Нужен id. | `{id:"minecraft:mob_spawner",SpawnData:{entity:{id:"minecraft:spider"}}}` |
| `block_state` | состояние блока | Свойства блока при установке: ключ → значение строкой. | `{type:"top"}` |
| `blocks_attacks` | блокирует удары | Работает как щит: какой урон и сколько гасит. | `{block_delay_seconds:0.25f,damage_reductions:[{base:0.0f,factor:0.5f}]}` |
| `break_sound` | звук поломки | Звук, когда предмет ломается. | `"minecraft:item.wolf_armor.break"` |
| `bucket_entity_data` | данные из ведра | Поля рыбы или аксолотля из ведра. | `{NoAI:1b,Glowing:1b}` |
| `bundle_contents` | содержимое мешка | Предметы внутри мешка. | `[{id:"minecraft:diamond",count:2}]` |
| `can_break` | может ломать | Какие блоки можно ломать в режиме приключения. | `{blocks:["minecraft:coal_ore","minecraft:iron_ore"]}` |
| `can_place_on` | можно ставить на | На какие блоки можно ставить в режиме приключения. | `{blocks:"minecraft:sandstone"}` |
| `cat/collar` | кошка: ошейник | Каким появится существо из яйца призыва, ведра или другого предмета. | `"blue"` |
| `cat/sound_variant` | кошка: голос | Каким появится существо из яйца призыва, ведра или другого предмета. | `"royal"` |
| `cat/variant` | кошка: окрас | Каким появится существо из яйца призыва, ведра или другого предмета. | `"jellie"` |
| `charged_projectiles` | заряд арбалета | Снаряды, которыми заряжен арбалет. | `[{id:"minecraft:spectral_arrow"}]` |
| `chicken/sound_variant` | курица: голос | Каким появится существо из яйца призыва, ведра или другого предмета. | `"picky"` |
| `chicken/variant` | курица: вид | Каким появится существо из яйца призыва, ведра или другого предмета. | `"cold"` |
| `consumable` | можно съесть | Предмет употребляется по ПКМ: время, анимация, звук, эффекты. | `{consume_seconds:1.6f,animation:"eat",sound:"entity.generic.eat",has_consume_particles:true}` |
| `container` | содержимое | Предметы в слотах контейнера, до 256. | `[{slot:0,item:{id:"minecraft:apple",count:1}}]` |
| `container_loot` | таблица добычи | Таблица добычи и зерно для контейнера. | `{loot_table:"minecraft:chests/desert_pyramid"}` |
| `cow/sound_variant` | корова: голос | Каким появится существо из яйца призыва, ведра или другого предмета. | `"moody"` |
| `cow/variant` | корова: вид | Каким появится существо из яйца призыва, ведра или другого предмета. | `"cold"` |
| `creative_slot_lock` | замок слота | Служебный: предмет нельзя вынуть из слота творческого инвентаря. | `{}` |
| `custom_data` | свои данные | Произвольные ключи и значения. Игра их не читает — это метки для своего кода. | `{tag:"value",level:1}` |
| `custom_model_data` | данные модели | Числа, флаги, строки и цвета для выбора модели в ресурспаке. | `{floats:[1.0f],strings:["red"]}` |
| `custom_name` | своё имя | Имя, как с наковальни или бирки. Наклонное, если не поставить italic:false. | `{text:"Волшебная палочка",color:"light_purple",italic:false}` |
| `damage` | износ | Сколько прочности уже потрачено — не остаток, а расход. | `10` |
| `damage_resistant` | стойкость к урону | Лежащий или надетый предмет не получает урон этих типов. | `{types:"#minecraft:is_fire"}` |
| `damage_type` | тип урона | Каким типом урона бьёт предмет. | `"minecraft:campfire"` |
| `death_protection` | спасает от смерти | Работает как тотем: спасает от смерти и применяет эффекты. | `{death_effects:[{type:"minecraft:clear_all_effects"}]}` |
| `debug_stick_state` | палка отладки | Какое свойство меняет палка отладки у каждого блока. | `{"minecraft:oak_fence":"east"}` |
| `dye` | как краситель | Предмет работает как краситель этого цвета. | `"red"` |
| `dyed_color` | цвет окраски | Цвет окраски (кожаная броня и т. п.): число RGB. | `16711680` |
| `enchantable` | зачаровываемость | Зачаровываемость на столе; нужны ещё enchantments. | `{value:15}` |
| `enchantment_glint_override` | блеск чар | true — блестит без чар, false — не блестит даже с чарами. | `true` |
| `enchantments` | чары | Действующие зачарования: id → уровень. | `{"minecraft:sharpness":3,"minecraft:knockback":2}` |
| `entity_data` | данные сущности | NBT сущности, которую создаёт предмет (яйцо, стойка). Нужен id. | `{id:"minecraft:armor_stand",Small:1b}` |
| `equippable` | надевается | Предмет можно надеть в слот: звук, модель брони, оверлей. | `{slot:"head",equip_sound:"item.armor.equip_generic",dispensable:true}` |
| `firework_explosion` | взрыв звезды | Взрыв фейерверочной звезды. | `{shape:"star",colors:[I;16711680],has_trail:true}` |
| `fireworks` | ракета | Длительность полёта и взрывы ракеты. | `{flight_duration:2b,explosions:[{shape:"large_ball",colors:[I;65280]}]}` |
| `food` | еда | Сколько голода и насыщения восстанавливает. Съесть можно, только если есть consumable. | `{nutrition:4,saturation:2.4f,can_always_eat:true}` |
| `fox/variant` | лиса: вид | Каким появится существо из яйца призыва, ведра или другого предмета. | `"snow"` |
| `frog/variant` | лягушка: вид | Каким появится существо из яйца призыва, ведра или другого предмета. | `"cold"` |
| `glider` | планер | Надетый предмет даёт планировать, как элитры. | `{}` |
| `horse/variant` | лошадь: окрас | Каким появится существо из яйца призыва, ведра или другого предмета. | `"chestnut"` |
| `instrument` | звук рога | Инструмент козьего рога: id или своё описание. | `"minecraft:feel_goat_horn"` |
| `intangible_projectile` | неподбираемый снаряд | Выпущенную стрелу или трезубец нельзя подобрать (кроме творческого). | `{}` |
| `item_model` | модель | Какой моделью рисовать предмет: assets/&lt;пространство>/items/&lt;id>. | `"minecraft:diamond_sword"` |
| `item_name` | имя предмета | Базовое имя: не наклонное, наковальня его не стирает. | `{text:"Ключ",color:"gold"}` |
| `jukebox_playable` | пластинка | Какую мелодию играет в проигрывателе. | `"minecraft:pigstep"` |
| `kinetic_weapon` | таранный удар | Атака разгоном, как у копья: урон зависит от скорости. | `{delay_ticks:10,damage_conditions:{max_duration_ticks:100},damage_multiplier:1.0f}` |
| `llama/variant` | лама: окрас | Каким появится существо из яйца призыва, ведра или другого предмета. | `"gray"` |
| `lock` | замок | Контейнер откроется, только если в руке подходящий предмет. | `{components:{"minecraft:custom_name":"Ключ"}}` |
| `lodestone_tracker` | компас на магнетит | Куда указывает компас: позиция и измерение. | `{target:{pos:[I;0,64,0],dimension:"minecraft:overworld"},tracked:false}` |
| `lore` | описание | Строки под именем, до 256. | `[{text:"Строка описания",color:"gray",italic:false}]` |
| `map_color` | цвет карты | Цвет пометок на значке карты: число RGB. | `16711680` |
| `map_decorations` | значки на карте | Значки на карте: ключ → тип, x, z, поворот. | `{mark:{type:"red_x",x:0.0d,z:0.0d,rotation:0.0f}}` |
| `map_id` | номер карты | Номер данных карты. | `0` |
| `map_post_processing` | обработка карты | Служебный: 0 — заблокировать, 1 — масштаб. | `0` |
| `max_damage` | прочность | Сколько раз предмет можно использовать до поломки. Не сочетается со стопкой больше 1. | `250` |
| `max_stack_size` | размер стопки | Сколько штук помещается в стопку: 1–99. Больше 1 нельзя вместе с max_damage. | `16` |
| `minimum_attack_charge` | мин. заряд удара | Насколько должна заполниться шкала атаки, чтобы ударить: 0–1. | `0.5f` |
| `mooshroom/variant` | грибная корова: вид | Каким появится существо из яйца призыва, ведра или другого предмета. | `"brown"` |
| `note_block_sound` | звук нотного блока | Звук нотного блока, если голова стоит сверху. | `"minecraft:entity.item.pickup"` |
| `ominous_bottle_amplifier` | сила зловещей бутылки | Уровень «Дурного знамения»: 0–4. | `2` |
| `painting/variant` | картина | Какую картину повесит предмет. | `"minecraft:plant"` |
| `parrot/variant` | попугай: вид | Каким появится существо из яйца призыва, ведра или другого предмета. | `"blue"` |
| `piercing_weapon` | пронзающий удар | Удар бьёт всех по лучу, а не одну цель; копать им нельзя. | `{deals_knockback:true,dismounts:false}` |
| `pig/sound_variant` | свинья: голос | Каким появится существо из яйца призыва, ведра или другого предмета. | `"big"` |
| `pig/variant` | свинья: вид | Каким появится существо из яйца призыва, ведра или другого предмета. | `"warm"` |
| `pot_decorations` | черепки горшка | Четыре стороны горшка: кирпич или черепок. | `["minecraft:brick","minecraft:angler_pottery_sherd","minecraft:brick","minecraft:brick"]` |
| `potion_contents` | зелье | Базовое зелье, свои эффекты и цвет. | `{potion:"minecraft:swiftness",custom_color:16711680,custom_effects:[{id:"minecraft:speed",amplifier:1b,duration:600}]}` |
| `potion_duration_scale` | длительность зелья × | Множитель длительности эффектов зелья. | `2.0f` |
| `profile` | голова игрока | Скин головы: ник, UUID или готовые свойства. | `"Notch"` |
| `provides_banner_patterns` | узоры для станка | Какие узоры даёт в ткацком станке: id, #тег или список. | `"#minecraft:pattern_item/globe"` |
| `provides_trim_material` | материал отделки | Предмет даёт этот материал отделки в кузнице. | `"minecraft:emerald"` |
| `rabbit/variant` | кролик: вид | Каким появится существо из яйца призыва, ведра или другого предмета. | `"gold"` |
| `rarity` | редкость | Цвет имени по умолчанию. | `"epic"` |
| `recipes` | рецепты | Рецепты, которые открывает книга знаний. | `["minecraft:diamond_block"]` |
| `repair_cost` | цена починки | Сколько уровней добавляется к цене на наковальне. | `0` |
| `repairable` | чем чинить | Чем чинить предмет на наковальне. | `{items:"minecraft:stick"}` |
| `salmon/size` | лосось: размер | Каким появится существо из яйца призыва, ведра или другого предмета. | `"large"` |
| `sheep/color` | овца: шерсть | Каким появится существо из яйца призыва, ведра или другого предмета. | `"pink"` |
| `shulker/color` | шалкер: цвет | Каким появится существо из яйца призыва, ведра или другого предмета. | `"red"` |
| `stored_enchantments` | чары в книге | Неактивные чары, как в зачарованной книге: id → уровень. | `{"minecraft:mending":1}` |
| `sulfur_cube_content` | в серном кубе | Предмет внутри серного куба. | `{id:"minecraft:diamond",count:1}` |
| `suspicious_stew_effects` | эффекты рагу | Эффекты подозрительного рагу: id и длительность в тиках. | `[{id:"minecraft:night_vision",duration:160}]` |
| `swing_animation` | анимация взмаха | Как машет рукой при ударе. | `{type:"stab",duration:6}` |
| `tool` | инструмент | Скорость добычи, износ за блок и правила для блоков. | `{default_mining_speed:1.0f,damage_per_block:1,rules:[{blocks:"#minecraft:mineable/pickaxe",speed:6.0f,correct_for_drops:true}]}` |
| `tooltip_display` | скрыть в описании | Прячет всё описание или строки отдельных компонентов. | `{hidden_components:["minecraft:enchantments"]}` |
| `tooltip_style` | стиль описания | Свои спрайты фона и рамки описания из ресурспака. | `"xerocode:frame"` |
| `trim` | отделка брони | Узор и материал отделки брони. | `{pattern:"minecraft:host",material:"minecraft:emerald"}` |
| `tropical_fish/base_color` | тропическая рыба: цвет | Каким появится существо из яйца призыва, ведра или другого предмета. | `"red"` |
| `tropical_fish/pattern` | тропическая рыба: узор | Каким появится существо из яйца призыва, ведра или другого предмета. | `"snooper"` |
| `tropical_fish/pattern_color` | тропическая рыба: цвет узора | Каким появится существо из яйца призыва, ведра или другого предмета. | `"blue"` |
| `unbreakable` | неразрушимый | Предмет не тратит прочность, в описании появляется «Неразрушимый». | `{}` |
| `use_cooldown` | перезарядка | Задержка после использования — для всех таких предметов или своей группы. | `{seconds:3.0f,cooldown_group:"xerocode:wand"}` |
| `use_effects` | пока используют | Что происходит, пока держат ПКМ: можно ли бежать и насколько замедляет. | `{can_sprint:true,speed_multiplier:1.0f}` |
| `use_remainder` | остаток | Во что превращается предмет после использования. | `{id:"minecraft:bowl",count:1}` |
| `villager/variant` | житель: вид | Каким появится существо из яйца призыва, ведра или другого предмета. | `"desert"` |
| `weapon` | оружие | Износ за удар и сколько секунд отключает щит. | `{item_damage_per_attack:1,disable_blocking_for_seconds:5.0f}` |
| `wolf/collar` | волк: ошейник | Каким появится существо из яйца призыва, ведра или другого предмета. | `"blue"` |
| `wolf/sound_variant` | волк: голос | Каким появится существо из яйца призыва, ведра или другого предмета. | `"cute"` |
| `wolf/variant` | волк: вид | Каким появится существо из яйца призыва, ведра или другого предмета. | `"rusty"` |
| `writable_book_content` | книга с пером | Страницы книги с пером — простой текст. | `{pages:["Первая страница"]}` |
| `written_book_content` | подписанная книга | Название, автор и страницы подписанной книги. | `{title:"Книга",author:"XeroCode",pages:["Привет"]}` |
| `zombie_nautilus/variant` | зомби-наутилус: вид | Каким появится существо из яйца призыва, ведра или другого предмета. | `"warm"` |
