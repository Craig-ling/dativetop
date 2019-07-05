from wsgiref.simple_server import make_server
from pyramid.config import Configurator
from pyramid.request import Request
from pyramid.view import view_config

import json

demo_data = {
    'dative-url': 'http://127.0.0.1:5678/',
    'old-url': 'http://127.0.0.1:5679/',
    'old-instances': {
        'http://127.0.0.1:5679/bla': {
            'name': 'Blackfoot',
            'url': 'http://127.0.0.1:5679/bla',
            'leader': 'https://projects.linguistics.ubc.ca/blaold',
            'state': 'out-of-sync',
            'auto-sync?': False,
        },
        'http://127.0.0.1:5679/oka': {
            'name': 'Okanagan',
            'url': 'http://127.0.0.1:5679/oka',
            'leader': None,
            'state': None,
            'auto-sync?': False,
        },
        'http://127.0.0.1:5679/sta': {
            'name': "St'at'imcets",
            'url': 'http://127.0.0.1:5679/sta',
            'leader': 'https://projects.linguistics.ubc.ca/staold',
            'state': 'synced',
            'auto-sync?': True,
        },
    },
}


def is_valid_old(updated_old):
    print(updated_old)
    return True


def update_old(request):
    try:
        updated_old = request.json_body
    except Exception as exc:
        request.response.status = 400
        return {'error': 'Bad JSON in request body'}
    if not is_valid_old(updated_old):
        request.response.status = 400
        return {'error': 'The OLD provided in the update request was not valid'}
    old_url = updated_old['url']
    demo_data['old-instances'][old_url] = updated_old
    return demo_data


def data(request):
    if request.method == 'PUT':
        return update_old(request)
    return demo_data


if __name__ == '__main__':
    with Configurator() as config:
        config.include('cors')
        config.add_cors_preflight_handler()
        config.add_route('data', '/')
        config.add_view(data, route_name='data', renderer='json')
        app = config.make_wsgi_app()
    server = make_server('127.0.0.1', 6543, app)
    server.serve_forever()
