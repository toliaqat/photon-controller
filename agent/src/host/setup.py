# Copyright 2015 VMware, Inc. All Rights Reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License"); you may not
# use this file except in compliance with the License.  You may obtain a copy
# of the License at http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS, without
# warranties or conditions of any kind, EITHER EXPRESS OR IMPLIED.  See the
# License for then specific language governing permissions and limitations
# under the License.
from setuptools import setup, find_packages

with open('VERSION', 'r') as f:
    version = f.readline().strip()

setup(name='photon.controller.host',
      version=version,
      description="Photon Controller Host Service",
      author='VMware',
      author_email='support@vmware.com',
      url='http://www.vmware.com',
      packages=find_packages(),
      entry_points={
          "photon.controller.plugin": ["host = host.plugin:plugin"],
      },
      include_package_data=True,
      zip_safe=False,
      install_requires=[
          'enum34==0.9.19',
          'photon.controller.common',
          'futures==2.1.5',
          'thrift==0.9.3.1',
      ],
      extras_require={
          'esx': [
              'pyvmomi==6.0.0',
          ],
          'test': [
              'matchers==0.22',
              'Mock==1.0.1',
              'nose-testconfig==0.9',
              'pyhamcrest==1.8.0',
          ]
      },
      )
