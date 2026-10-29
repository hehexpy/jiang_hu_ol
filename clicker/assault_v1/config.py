"""单号首版：仅标定现有v13客户端/九州揽月/大理，所有位置为归一化值。"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / 'clicker'
MANIFEST = Path(__file__).parent / 'templates.json'
OUTPUT = ROOT / 'clicker/_sim/t027'
PACKAGE = 'com.good.mmo2'
SERVER = '九州揽月'
ACTIVITY = 'com.good.mmo2.PipActivity'
SETTLE = 1.2
RESPONSE_TIMEOUT = 95
POLL_INTERVAL = 2
THRESHOLD = .94
# source, crop: simultaneously template mapping and local anchor registration.
TEMPLATES = {
    'npc': ('01_city', (.3093, .2346, .6000, .3500)),
    'start': ('09_citywar', (.3296, .5846, .6796, .6197)),
    'header': ('10_citywar_start', (.30, .135, .70, .158)),
    'overview': ('10_citywar_start', (.05, .181, .20, .210)),
    'recruit_tab': ('10_citywar_start', (.4398, .1799, .5694, .2098)),
    'occupy_tab': ('20_my_troops', (.2593, .1799, .3796, .2107)),
    'recruit': ('11_recruit_p1', (.8000, .4308, .9194, .4658)),
    'recruit_name': ('11_recruit_p1', (.30, .367, .68, .390)),
    'available': ('11_recruit_p1', (.735, .365, .875, .390)),
    'funds': ('11_recruit_p1', (.385, .228, .59, .266)),
    'my_troops': ('11_recruit_p1', (.6000, .7308, .8398, .7688)),
    'recruit_list': ('20_my_troops', (.075, .730, .335, .768)),
    'recruit_confirm': ('12_recruit_dialog', (.075, .400, .925, .465)),
    'recruit_yes': ('12_recruit_dialog', (.12, .5547, .3694, .5906)),
    'confirm_title': ('12_recruit_dialog', (.39, .335, .61, .365)),
    'occupy_state': ('22_occupy', (.055, .36, .925, .39)),
    'city': ('22_occupy', (.11, .334, .205, .36)),
    'defend': ('22_occupy', (.8296, .3179, .95, .35)),
    'attack': ('22_occupy', (.8583, .5598, .9398, .6047)),
    'attack_target': ('22_occupy', (.055, .568, .365, .598)),
    'attack_target_alt1': ('25_occupy_return', (.055, .568, .365, .598)),
    'attack_target_alt2': ('32_occupy', (.055, .568, .365, .598)),
    'attack_confirm': ('26_attack_entry', (.115, .425, .91, .45)),
    'attack_yes': ('26_attack_entry', (.1194, .5547, .3694, .5906)),
    'closed': ('28_defend_entry', (.28, .430, .73, .457)),
    'army_limit': ('@/clicker/_sim/t027/20261018_171523_591934/_024_recruit_response.png', (.21, .430, .79, .457)),
    'error_title': ('28_defend_entry', (.46, .395, .55, .418)),
    'close_error': ('28_defend_entry', (.82, .393, .88, .421)),
    'page_1': ('20_my_troops', (.465, .77, .57, .79)),
    'page_2': ('21_my_troops_p2', (.465, .77, .57, .79)),
    'next': ('20_my_troops', (.68, .773, .72, .797)),
    'prev': ('21_my_troops_p2', (.29, .773, .335, .797)),
    'rest': ('20_my_troops', (.43, .430, .565, .461)),
    'owned_red': ('20_my_troops', (.31, .486, .70, .510)),
}
ROW_SPACING = .118


def template_path(name):
    return ASSETS / ('tpl_assault_' + name + '.png')
