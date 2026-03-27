FROM arl-agent-base

USER root

# Install shared booksnap_labs module (build context is booksnap root)
COPY shared/python /tmp/booksnap-labs
RUN uv pip install --system /tmp/booksnap-labs && rm -rf /tmp/booksnap-labs

USER agent
